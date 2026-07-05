import { useState, useEffect } from 'react'

// Demo/mock mod za prikaz dizajna bez pokrenutog backend-a: ?mock=1
const MOCK = typeof window !== 'undefined' &&
  new URLSearchParams(window.location.search).get('mock') === '1'

const MOCK_DATA = {
  8081: { sentCount: 1284500, deviceCount: 100 },
  8082: { totalRecords: 1281000, lastBatchSize: 500, totalFlushed: 1281000 },
  8083: {
    lastWindowMessages: 1000,
    avgTemperature: 21.34,
    alert: false,
    p50: 4,
    p95: 6,
    p99: 8,
    eventCount: 342,
    eventsByType: { HIGH_TEMPERATURE: 210, LOW_TEMPERATURE: 132 },
    predictedTemperature: 21.02,
    lastEvents: [
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_7', temperature: 30.4, location: 'Room A' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_2', temperature: 8.1, location: 'Outside' },
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_15', temperature: 31.2, location: 'Room C' },
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_4', temperature: 29.8, location: 'Room B' },
      { event_type: 'LOW_TEMPERATURE', device_id: 'Device_11', temperature: 7.6, location: 'Outside' },
      { event_type: 'HIGH_TEMPERATURE', device_id: 'Device_9', temperature: 30.1, location: 'Room A' },
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
