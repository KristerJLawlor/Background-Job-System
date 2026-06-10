import { useState, useRef } from 'react'

export default function FileUploadTab({ onSubmit }) {
  const [files, setFiles] = useState([])
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef(null)

  const addFiles = (incoming) => {
    const accepted = Array.from(incoming).filter(f => f.type.startsWith('image/'))
    setFiles(prev => {
      const existing = new Set(prev.map(f => f.name + f.size))
      return [...prev, ...accepted.filter(f => !existing.has(f.name + f.size))]
    })
  }

  const removeFile = (idx) => setFiles(prev => prev.filter((_, i) => i !== idx))

  const handleDrop = (e) => {
    e.preventDefault()
    setDragging(false)
    addFiles(e.dataTransfer.files)
  }

  const handleSubmit = () => {
    if (files.length === 0) return
    onSubmit(files)
    setFiles([])
  }

  return (
    <div className="upload-tab">
      <div
        className={`drop-zone${dragging ? ' dragging' : ''}`}
        onClick={() => inputRef.current?.click()}
        onDrop={handleDrop}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
      >
        <span className="drop-icon">📁</span>
        <p>Drop images here or <strong>click to browse</strong></p>
        <p className="drop-hint">.png · .jpg · .gif — max 10 MB each</p>
        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          multiple
          style={{ display: 'none' }}
          onChange={e => { addFiles(e.target.files); e.target.value = '' }}
        />
      </div>

      {files.length > 0 && (
        <ul className="file-list">
          {files.map((f, i) => (
            <li key={i} className="file-item">
              <span className="file-name">{f.name}</span>
              <span className="file-size">{(f.size / 1024).toFixed(1)} KB</span>
              <button className="remove-btn" onClick={() => removeFile(i)}>✕</button>
            </li>
          ))}
        </ul>
      )}

      <button
        className="submit-btn"
        disabled={files.length === 0}
        onClick={handleSubmit}
      >
        Process {files.length > 0 ? `${files.length} file${files.length !== 1 ? 's' : ''}` : 'Files'}
      </button>
    </div>
  )
}
