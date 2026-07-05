// MaaS predikcija: RandomForest regresija (FastAPI) predviđa temperaturu iz
// ostalih senzorskih veličina; poredi se sa stvarnim prosekom tumbling window-a.
const SCALE = 60 // °C skala prikaza (prag alarma je 50°C)

export default function MaasCard({ data, error }) {
  const pred = data?.predictedTemperature
  const actual = data?.avgTemperature
  const has = pred != null && actual != null
  const delta = has ? pred - actual : null
  const absDelta = delta != null ? Math.abs(delta) : null

  const deltaClass =
    absDelta == null ? '' : absDelta < 1 ? 'good' : absDelta < 3 ? 'warn' : 'bad'

  const pct = (v) => Math.max(2, Math.min(100, (v / SCALE) * 100))

  return (
    <article className="card card-maas">
      <header className="card-head">
        <div className="card-title">
          <span className="card-kicker maas">MaaS · Model-as-a-Service</span>
          <h2>Predikcija temperature</h2>
        </div>
        <span className="chip chip-maas">RandomForest · FastAPI</span>
      </header>

      {error ? (
        <p className="state offline">Servis nedostupan</p>
      ) : !data ? (
        <p className="state loading">Povezivanje…</p>
      ) : !has ? (
        <p className="state loading">Čekam prvi prozor…</p>
      ) : (
        <>
          <div className="compare">
            <Row
              label="Predviđeno (model)"
              value={pred}
              cls="pred"
              width={pct(pred)}
            />
            <Row
              label="Stvarni prosek (window)"
              value={actual}
              cls="actual"
              width={pct(actual)}
            />
            <div className="threshold" style={{ left: `${pct(50)}%` }}>
              <span className="threshold-mark" />
              <span className="threshold-label">prag 50°C</span>
            </div>
          </div>

          <div className="maas-foot">
            <div className={`delta-chip ${deltaClass}`}>
              <span className="delta-arrow">{delta >= 0 ? '▲' : '▼'}</span>
              <span>Δ {absDelta.toFixed(2)} °C</span>
              <span className="delta-note">odstupanje modela</span>
            </div>
            <p className="maas-hint">
              Model predviđa temperaturu iz <em>humidity, pressure, light, sound,
              motion, location</em> — Analytics ga zove REST-om na kraju svakog prozora.
            </p>
          </div>
        </>
      )}
    </article>
  )
}

function Row({ label, value, cls, width }) {
  return (
    <div className="compare-row">
      <div className="compare-meta">
        <span className="compare-label">{label}</span>
        <span className={`compare-value ${cls}`}>{value.toFixed(2)} °C</span>
      </div>
      <div className="bar-track">
        <div className={`bar-fill ${cls}`} style={{ width: `${width}%` }} />
      </div>
    </div>
  )
}
