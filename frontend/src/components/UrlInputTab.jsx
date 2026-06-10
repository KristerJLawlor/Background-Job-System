import { useState } from 'react'

export default function UrlInputTab({ onSubmit }) {
  const [text, setText] = useState('')

  const urls = text.split('\n').map(s => s.trim()).filter(Boolean)

  const handleSubmit = () => {
    if (urls.length === 0) return
    onSubmit(urls)
    setText('')
  }

  return (
    <div className="url-tab">
      <textarea
        className="url-textarea"
        value={text}
        onChange={e => setText(e.target.value)}
        placeholder={'https://example.com/avatar.png\nhttps://example.com/profile.gif\nOne URL per line'}
        rows={6}
      />
      <p className="url-hint">
        {urls.length > 0
          ? `${urls.length} URL${urls.length !== 1 ? 's' : ''} ready`
          : 'Enter one URL per line'}
      </p>
      <button
        className="submit-btn"
        disabled={urls.length === 0}
        onClick={handleSubmit}
      >
        Process {urls.length > 0 ? `${urls.length} URL${urls.length !== 1 ? 's' : ''}` : 'URLs'}
      </button>
    </div>
  )
}
