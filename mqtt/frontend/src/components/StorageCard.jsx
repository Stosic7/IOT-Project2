import { usePolling } from '../hooks/usePolling'

export default function StorageCard() {
  const { data, error } = usePolling('http://localhost:8082/api/stats')

  return (
    <div className="card">
      <h2>Storage Service</h2>
      {error ? (
        <p className="offline">Offline</p>
      ) : !data ? (
        <p className="loading">Connecting...</p>
      ) : (
        <>
          <Stat label="Total Records in DB" value={data.totalRecords.toLocaleString()} />
          <Stat label="Last Batch Size" value={data.lastBatchSize} />
          <Stat label="Total Flushed" value={data.totalFlushed.toLocaleString()} />
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