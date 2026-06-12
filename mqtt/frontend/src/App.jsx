import IngestionCard from './components/IngestionCard'
import StorageCard from './components/StorageCard'
import AnalyticsCard from './components/AnalyticsCard'
import './App.css'

export default function App() {
  return (
    <div className="app">
      <header className="header">
        <h1>IoT MQTT Dashboard</h1>
        <span className="subtitle">Live — refresh every 2s</span>
      </header>
      <main className="grid">
        <IngestionCard />
        <StorageCard />
        <AnalyticsCard />
      </main>
    </div>
  )
}