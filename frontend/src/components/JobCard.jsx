const STATUS_COLORS = {
  SUBMITTING: '#6b7280',
  PENDING:    '#f59e0b',
  PROCESSING: '#3b82f6',
  COMPLETED:  '#10b981',
  FAILED:     '#ef4444',
}

const STATUS_LABELS = {
  SUBMITTING: 'Submitting…',
  PENDING:    'Pending',
  PROCESSING: 'Processing…',
  COMPLETED:  'Done',
  FAILED:     'Failed',
}

export default function JobCard({ job }) {
  const handleDownload = () => {
    if (!job.result?.url) return
    const a = document.createElement('a')
    a.href = job.result.url
    const baseName = job.name.replace(/\.[^.]+$/, '') || 'avatar'
    a.download = `${baseName}_avatar.${job.result.ext}`
    a.click()
  }

  return (
    <div className="job-card">
      <div className="job-info">
        <span className="job-name" title={job.name}>{job.name}</span>
        {job.error && <span className="job-error">{job.error}</span>}
      </div>
      <div className="job-actions">
        <span
          className="status-badge"
          style={{ backgroundColor: STATUS_COLORS[job.status] ?? '#6b7280' }}
        >
          {STATUS_LABELS[job.status] ?? job.status}
        </span>
        {job.status === 'COMPLETED' && job.result?.url && (
          <button className="download-btn" onClick={handleDownload}>
            Download
          </button>
        )}
      </div>
    </div>
  )
}
