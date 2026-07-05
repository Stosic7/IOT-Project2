// eKuiper CEP: pravila nad iot/sensors/# detektuju kritične temperature i
// publikuju događaje na iot/events, koje Analytics ovde prikazuje uživo.
const TYPE_META = {
  HIGH_TEMPERATURE: { label: 'Visoka temp.', cls: 'hot', icon: '🔥' },
  LOW_TEMPERATURE: { label: 'Niska temp.', cls: 'cold', icon: '❄️' },
}

export default function EventsCard({ data, error }) {
  const total = data?.eventCount
  const byType = data?.eventsByType ?? {}
  const feed = data?.lastEvents ?? []

  return (
    <article className="card card-events">
      <header className="card-head">
        <div className="card-title">
          <span className="card-kicker cep">eKuiper · CEP</span>
          <h2>Detektovani događaji</h2>
        </div>
        <span className="chip chip-cep">iot/events</span>
      </header>

      {error ? (
        <p className="state offline">Servis nedostupan</p>
      ) : !data ? (
        <p className="state loading">Povezivanje…</p>
      ) : (
        <>
          <div className="ev-total">
            <span className="ev-total-value">{(total ?? 0).toLocaleString()}</span>
            <span className="ev-total-label">ukupno događaja</span>
          </div>

          <div className="ev-types">
            {['HIGH_TEMPERATURE', 'LOW_TEMPERATURE'].map((t) => {
              const meta = TYPE_META[t]
              return (
                <div className={`ev-type ${meta.cls}`} key={t}>
                  <span className="ev-type-icon">{meta.icon}</span>
                  <span className="ev-type-count">{(byType[t] ?? 0).toLocaleString()}</span>
                  <span className="ev-type-label">{meta.label}</span>
                </div>
              )
            })}
          </div>

          <div className="ev-feed-head">Poslednji događaji</div>
          {feed.length === 0 ? (
            <p className="ev-empty">Nema još detektovanih događaja…</p>
          ) : (
            <ul className="ev-feed">
              {feed.slice(0, 7).map((e, i) => {
                const meta = TYPE_META[e.event_type] ?? { cls: 'neutral', icon: '•' }
                return (
                  <li className={`ev-item ${meta.cls}`} key={`${e.device_id}-${i}`}>
                    <span className="ev-item-icon">{meta.icon}</span>
                    <span className="ev-item-device">{e.device_id}</span>
                    <span className="ev-item-loc">{e.location}</span>
                    <span className="ev-item-temp">{Number(e.temperature).toFixed(1)}°C</span>
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </article>
  )
}
