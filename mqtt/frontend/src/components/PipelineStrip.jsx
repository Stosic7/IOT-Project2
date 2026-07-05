import { usePolling } from '../hooks/usePolling'

// Kontekst: dokaz da ceo pipeline živi — Ingestion šalje, Storage upisuje u bazu.
export default function PipelineStrip() {
  const ing = usePolling('http://localhost:8081/api/stats')
  const sto = usePolling('http://localhost:8082/api/stats')

  return (
    <div className="strip">
      <Tile
        icon="📡"
        title="Ingestion"
        online={!ing.error && !!ing.data}
        stats={[
          ['Poslato poruka', ing.data?.sentCount?.toLocaleString()],
          ['Uređaja', ing.data?.deviceCount?.toLocaleString()],
        ]}
      />
      <Tile
        icon="💾"
        title="Storage → PostgreSQL"
        online={!sto.error && !!sto.data}
        stats={[
          ['Zapisa u bazi', sto.data?.totalRecords?.toLocaleString()],
          ['Batch', sto.data?.lastBatchSize?.toLocaleString()],
        ]}
      />
    </div>
  )
}

function Tile({ icon, title, stats, online }) {
  return (
    <div className="strip-tile">
      <div className="strip-top">
        <span className="strip-icon">{icon}</span>
        <span className="strip-title">{title}</span>
        <span className={`strip-dot ${online ? 'on' : 'off'}`} />
      </div>
      <div className="strip-stats">
        {stats.map(([label, value]) => (
          <div className="strip-stat" key={label}>
            <span className="strip-value">{value ?? '—'}</span>
            <span className="strip-label">{label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
