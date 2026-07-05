// Tumbling window (10s) agregacija + end-to-end latencija + alarm > 50°C.
export default function WindowCard({ data, error }) {
  const alert = !!data?.alert
  const avg = data?.avgTemperature
  const msgs = data?.lastWindowMessages

  return (
    <article className={`card card-window ${alert ? 'is-alert' : ''}`}>
      <header className="card-head">
        <div className="card-title">
          <span className="card-kicker ana">Analytics · Stream</span>
          <h2>Tumbling Window (10s)</h2>
        </div>
        {alert ? (
          <span className="chip chip-alert">ALERT &gt; 50°C</span>
        ) : (
          <span className="chip chip-ok">u granicama</span>
        )}
      </header>

      {error ? (
        <p className="state offline">Servis nedostupan</p>
      ) : !data ? (
        <p className="state loading">Povezivanje…</p>
      ) : (
        <>
          <div className="win-stats">
            <div className="win-stat">
              <span className="win-value">{(msgs ?? 0).toLocaleString()}</span>
              <span className="win-label">poruka u prozoru</span>
            </div>
            <div className="win-stat">
              <span className={`win-value ${alert ? 'hot' : ''}`}>
                {avg != null ? avg.toFixed(2) : '—'}
                <span className="win-unit">°C</span>
              </span>
              <span className="win-label">prosečna temperatura</span>
            </div>
          </div>

          <div className="lat-head">
            End-to-end latencija <span className="lat-sub">(sent → obrađeno)</span>
          </div>
          <div className="lat-row">
            <Lat label="p50" value={data.p50} />
            <Lat label="p95" value={data.p95} />
            <Lat label="p99" value={data.p99} />
          </div>
        </>
      )}
    </article>
  )
}

function Lat({ label, value }) {
  return (
    <div className="lat-tile">
      <span className="lat-label">{label}</span>
      <span className="lat-value">{value ?? 0}<span className="lat-ms">ms</span></span>
    </div>
  )
}
