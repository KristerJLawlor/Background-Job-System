import { useState, useEffect, useRef } from 'react'
import FileUploadTab from './components/FileUploadTab.jsx'
import UrlInputTab from './components/UrlInputTab.jsx'
import JobCard from './components/JobCard.jsx'
import { submitUrl, uploadFile, getStatus, getResult } from './api.js'

export default function App() {
  const [tab, setTab] = useState('upload')
  const [jobs, setJobs] = useState([])
  const [apiKey, setApiKey] = useState(() => localStorage.getItem('apiKey') ?? 'changeme')
  const [showSettings, setShowSettings] = useState(false)

  // Refs so the polling interval always reads current values without re-creating itself.
  const jobsRef = useRef(jobs)
  const apiKeyRef = useRef(apiKey)
  const claimingRef = useRef(new Set())
  jobsRef.current = jobs
  apiKeyRef.current = apiKey

  useEffect(() => {
    const timer = setInterval(async () => {
      const active = jobsRef.current.filter(
        j => j.id && !['COMPLETED', 'FAILED', 'SUBMITTING'].includes(j.status)
      )
      if (!active.length) return

      await Promise.allSettled(active.map(async job => {
        try {
          const status = await getStatus(job.id, apiKeyRef.current)
          if (status === 'COMPLETED' && !claimingRef.current.has(job.id)) {
            claimingRef.current.add(job.id)
            try {
              const result = await getResult(job.id, apiKeyRef.current)
              setJobs(prev => prev.map(j =>
                j.uiKey === job.uiKey ? { ...j, status: 'COMPLETED', result } : j
              ))
            } finally {
              claimingRef.current.delete(job.id)
            }
          } else if (status !== job.status && status !== 'COMPLETED') {
            setJobs(prev => prev.map(j =>
              j.uiKey === job.uiKey ? { ...j, status } : j
            ))
          }
        } catch {
          // ignore transient poll errors
        }
      }))
    }, 2000)

    return () => clearInterval(timer)
  }, []) // single interval for the lifetime of the app

  const handleFileSubmit = async (fileList) => {
    const files = Array.from(fileList)
    const pending = files.map(f => ({
      uiKey: crypto.randomUUID(),
      id: null,
      name: f.name,
      status: 'SUBMITTING',
      result: null,
    }))
    setJobs(prev => [...pending, ...prev])

    await Promise.allSettled(files.map(async (file, i) => {
      const { uiKey } = pending[i]
      try {
        const jobId = await uploadFile(file, apiKeyRef.current)
        setJobs(prev => prev.map(j =>
          j.uiKey === uiKey ? { ...j, id: jobId, status: 'PENDING' } : j
        ))
      } catch (err) {
        setJobs(prev => prev.map(j =>
          j.uiKey === uiKey ? { ...j, status: 'FAILED', error: err.message } : j
        ))
      }
    }))
  }

  const handleUrlSubmit = async (urls) => {
    const pending = urls.map(url => ({
      uiKey: crypto.randomUUID(),
      id: null,
      name: url,
      status: 'SUBMITTING',
      result: null,
    }))
    setJobs(prev => [...pending, ...prev])

    await Promise.allSettled(urls.map(async (url, i) => {
      const { uiKey } = pending[i]
      try {
        const jobId = await submitUrl(url, apiKeyRef.current)
        setJobs(prev => prev.map(j =>
          j.uiKey === uiKey ? { ...j, id: jobId, status: 'PENDING' } : j
        ))
      } catch (err) {
        setJobs(prev => prev.map(j =>
          j.uiKey === uiKey ? { ...j, status: 'FAILED', error: err.message } : j
        ))
      }
    }))
  }

  const saveApiKey = (key) => {
    setApiKey(key)
    localStorage.setItem('apiKey', key)
  }

  return (
    <div className="app">
      <header className="header">
        <h1 className="title">Avatar Resizer</h1>
        <button className="icon-btn" onClick={() => setShowSettings(s => !s)} title="Settings">
          ⚙
        </button>
      </header>

      {showSettings && (
        <div className="settings-panel">
          <label className="settings-label">
            API Key
            <input
              className="settings-input"
              type="password"
              value={apiKey}
              onChange={e => saveApiKey(e.target.value)}
              autoComplete="off"
            />
          </label>
          <p className="settings-hint">
            Stored in browser localStorage. Default is <code>changeme</code>.
          </p>
        </div>
      )}

      <div className="tabs">
        <button
          className={`tab-btn${tab === 'upload' ? ' active' : ''}`}
          onClick={() => setTab('upload')}
        >
          Upload Files
        </button>
        <button
          className={`tab-btn${tab === 'url' ? ' active' : ''}`}
          onClick={() => setTab('url')}
        >
          Enter URLs
        </button>
      </div>

      <div className="tab-content">
        {tab === 'upload'
          ? <FileUploadTab onSubmit={handleFileSubmit} />
          : <UrlInputTab onSubmit={handleUrlSubmit} />}
      </div>

      {jobs.length > 0 && (
        <div className="jobs-section">
          <div className="jobs-header">
            <h2 className="jobs-title">Jobs</h2>
            <button className="clear-btn" onClick={() => setJobs([])}>Clear all</button>
          </div>
          <div className="jobs-list">
            {jobs.map(job => <JobCard key={job.uiKey} job={job} />)}
          </div>
        </div>
      )}
    </div>
  )
}
