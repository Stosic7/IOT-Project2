import { usePolling } from '../hooks/usePolling'

export default function AnalyticsCard() {
  const { data, error } = usePolling('http://localhost:8083/api/stats')

  return (
    <div className={`card ${data?.alert ? 'card-alert' : ''}`}>
      <h2>
        Analytics Service
        {data?.alert && <span className="alert-badge">ALERT</span>}
      </h2>
      {error ? (
        <p className="offline">Offline</p>
      ) : !data ? (
        <p className="loading">Connecting...</p>
      ) : (
        <>
          <Stat label="Window Messages (10s)" value={data.lastWindowMessages.toLocaleString()} />
          <Stat
            label="Avg Temperature"
            value={`${data.avgTemperature.toFixed(2)} °C`}
            highlight={data.alert}
          />
          <div className="latency-row">
            <LatencyStat label="p50" value={data.p50} />
            <LatencyStat label="p95" value={data.p95} />
            <LatencyStat label="p99" value={data.p99} />
          </div>
        </>
      )}
    </div>
  )
}

function Stat({ label, value, highlight }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className={`stat-value ${highlight ? 'text-alert' : ''}`}>{value}</span>
    </div>
  )
}

function LatencyStat({ label, value }) {
  return (
    <div className="latency-stat">
      <span className="latency-label">{label}</span>
      <span className="latency-value">{value} ms</span>
    </div>
  )
}
