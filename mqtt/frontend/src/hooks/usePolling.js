import { useState, useEffect } from 'react'

// Demo/mock mod za prikaz dizajna bez pokrenutog backend-a: ?mock=1
// Vrednosti su snimljene sa stvarnog run-a (100 uredjaja, QoS 1) — mock je replay tog merenja.
const MOCK = typeof window !== 'undefined' &&
  new URLSearchParams(window.location.search).get('mock') === '1'

const MOCK_DATA = {
  8081: { sentCount: 971600, deviceCount: 100 },
  8082: { totalRecords: 3865809, lastBatchSize: 500, totalFlushed: 3865809 },
  8083: {
    lastWindowMessages: 99416,
    avgTemperature: 20.08,
    alert: false,
    p50: 3,
    p95: 54,
    p99: 99,
    eventCount: 1998,
    eventsByType: { HIGH_TEMPERATURE: 1066, LOW_TEMPERATURE: 932 },
    predictedTemperature: 22.0,
    lastEvents: [
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_9', temperature: 30.1, location: 'Room A' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_40', temperature: 6.5, location: 'Outside' },
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_13', temperature: 29.6, location: 'Room A' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_47', temperature: 7.7, location: 'Outside' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_47', temperature: 7.6, location: 'Outside' },
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_7', temperature: 29.9, location: 'Room A' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_40', temperature: 7.5, location: 'Outside' },
    ],
  },
}

function mockFor(url) {
  const port = (url.match(/:(\d+)/) || [])[1]
  return MOCK_DATA[port] ?? {}
}

export function usePolling(url, interval = 2000) {
  const [data, setData] = useState(MOCK ? mockFor(url) : null)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (MOCK) {
      setData(mockFor(url))
      return
    }
    const fetch_ = () =>
      fetch(url)
        .then((r) => r.json())
        .then(setData)
        .catch(() => setError('offline'))

    fetch_()
    const id = setInterval(fetch_, interval)
    return () => clearInterval(id)
  }, [url, interval])

  return { data, error }
}
