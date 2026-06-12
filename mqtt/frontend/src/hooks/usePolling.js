
import { useState, useEffect } from 'react'

export function usePolling(url, interval = 2000) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    const fetch_ = () =>
      fetch(url)
        .then(r => r.json())
        .then(setData)
        .catch(() => setError('offline'))

    fetch_()
    const id = setInterval(fetch_, interval)
    return () => clearInterval(id)
  }, [url, interval])

  return { data, error }
}