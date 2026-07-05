import { Fragment } from 'react'

// Vizuelni prikaz toka podataka Projekta 3.
// eKuiper je pretplacen na iot/sensors/#, detektuje dogadjaje i publikuje ih
// na novi topic iot/events koji Analytics preuzima; Analytics zove MaaS REST.
const NODES = [
  { icon: '📡', label: 'IoT Sensori', sub: 'Ingestion Service' },
  { icon: '🦟', label: 'Mosquitto', sub: 'iot/sensors/#' },
  { icon: '⚙️', label: 'eKuiper', sub: 'CEP pravila', accent: 'cep' },
  { icon: '📊', label: 'Analytics', sub: 'Tumbling 10s', accent: 'ana' },
  { icon: '🧠', label: 'MaaS', sub: 'FastAPI /predict', accent: 'maas' },
]

export default function ArchitectureFlow({ data }) {
  const events = data?.eventCount
  const predicted = data?.predictedTemperature
  const windowMsgs = data?.lastWindowMessages

  const badgeFor = (i) => {
    if (i === 2 && events != null) return `${events.toLocaleString()} eventa`
    if (i === 3 && windowMsgs != null) return `${windowMsgs.toLocaleString()} msg`
    if (i === 4 && predicted != null) return `${predicted.toFixed(1)}°C`
    return null
  }

  return (
    <div className="flow-wrap">
      <div className="flow-head">
        <h3>Arhitektura podataka</h3>
        <span className="flow-tag">event-driven · MQTT pub/sub</span>
      </div>
      <div className="flow">
        {NODES.map((n, i) => (
          <Fragment key={n.label}>
            <div className={`node ${n.accent ? `node-${n.accent}` : ''} ${badgeFor(i) ? 'node-live' : ''}`}>
              <div className="node-icon">{n.icon}</div>
              <div className="node-label">{n.label}</div>
              <div className="node-sub">{n.sub}</div>
              {badgeFor(i) && <div className="node-badge">{badgeFor(i)}</div>}
            </div>
            {i < NODES.length - 1 && (
              <div className={`arrow ${i === 2 ? 'arrow-labeled' : ''} ${i === 3 ? 'arrow-bi' : ''}`}>
                <span className="arrow-line" />
                {i === 2 && <span className="arrow-topic">iot/events</span>}
                {i === 3 && <span className="arrow-topic">REST</span>}
                <span className="arrow-head">{i === 3 ? '⇄' : '→'}</span>
              </div>
            )}
          </Fragment>
        ))}
      </div>
    </div>
  )
}
