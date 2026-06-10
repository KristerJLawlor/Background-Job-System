export async function submitUrl(url, apiKey) {
  const res = await fetch(`/api/jobs?url=${encodeURIComponent(url)}`, {
    method: 'POST',
    headers: { 'X-Api-Key': apiKey },
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error || `HTTP ${res.status}`)
  }
  const data = await res.json()
  return data.jobId
}

export async function uploadFile(file, apiKey) {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch('/api/jobs/upload', {
    method: 'POST',
    headers: { 'X-Api-Key': apiKey },
    body: form,
  })
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error || `HTTP ${res.status}`)
  }
  const data = await res.json()
  return data.jobId
}

export async function getStatus(jobId, apiKey) {
  const res = await fetch(`/api/jobs/${jobId}`, {
    headers: { 'X-Api-Key': apiKey },
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.text()
}

// Returns null if the result was already claimed (410), or {url, contentType, ext} on success.
export async function getResult(jobId, apiKey) {
  const res = await fetch(`/api/jobs/${jobId}/result`, {
    headers: { 'X-Api-Key': apiKey },
  })
  if (res.status === 410) return null
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const blob = await res.blob()
  const contentType = res.headers.get('Content-Type') || 'image/png'
  const ext = contentType.includes('gif') ? 'gif' : 'png'
  return { url: URL.createObjectURL(blob), contentType, ext }
}
