import { usePolling } from '../hooks/usePolling'

export default function IngestionCard() {
  const { data, error } = usePolling('http://localhost:8081/api/stats')

  return (
    <div className="card">
      <h2>Ingestion Service</h2>
      {error ? (
        <p className="offline">Offline</p>
      ) : !data ? (
        <p className="loading">Connecting...</p>
      ) : (
        <>
          <Stat label="Messages Sent" value={data.sentCount.toLocaleString()} />
          <Stat label="Active Devices" value={data.deviceCount} />
          <Stat label="Rate" value="100 msg/s" />
        </>
      )}
    </div>
  )
}

function Stat({ label, value }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
    </div>
  )
}