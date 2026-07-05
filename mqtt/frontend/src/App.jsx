import { usePolling } from './hooks/usePolling'
import ArchitectureFlow from './components/ArchitectureFlow'
import PipelineStrip from './components/PipelineStrip'
import MaasCard from './components/MaasCard'
import EventsCard from './components/EventsCard'
import WindowCard from './components/WindowCard'
import './App.css'

const ANALYTICS = 'http://localhost:8083/api/stats'

export default function App() {
  // Jedan poll na Analytics endpoint — deli se izmedju MaaS / eKuiper / Window kartica.
  const { data, error } = usePolling(ANALYTICS)
  const live = !error && !!data

  return (
    <div className="app">
      <header className="hero">
        <div className="hero-main">
          <span className="hero-badge">Projekat 3</span>
          <h1>
            eKuiper <span className="amp">CEP</span> <span className="plus">+</span>{' '}
            <span className="grad">MaaS</span>
          </h1>
          <p className="hero-sub">
            Analytics mikroservis obogaćen streaming CEP obradom (eKuiper) i ML
            predikcijom temperature preko MaaS servisa — sve preko MQTT brokera.
          </p>
        </div>
        <div className={`live-pill ${live ? 'on' : 'off'}`}>
          <span className="live-dot" />
          {live ? 'LIVE' : 'OFFLINE'}
        </div>
      </header>

      <ArchitectureFlow data={data} />

      <PipelineStrip />

      <section className="grid">
        <MaasCard data={data} error={error} />
        <EventsCard data={data} error={error} />
        <WindowCard data={data} error={error} />
      </section>

      <footer className="foot">
        <span>Mosquitto → eKuiper → Analytics ⇄ MaaS</span>
        <span className="dot-sep">·</span>
        <span>PostgreSQL storage</span>
        <span className="dot-sep">·</span>
        <span>osvežavanje na 2s</span>
      </footer>
    </div>
  )
}
