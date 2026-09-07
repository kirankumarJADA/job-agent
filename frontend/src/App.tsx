import { useEffect, useState } from "react";

type HealthResponse = {
  status: string;
  timestamp: string;
  components: Record<string, string>;
};

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

export default function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${API_BASE_URL}/system/health`)
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(setHealth)
      .catch((err) => setError(String(err)));
  }, []);

  return (
    <main style={{ fontFamily: "system-ui", padding: "2rem" }}>
      <h1>Job Agent — dev skeleton</h1>
      <p>
        This is the Phase 1 (P1-a) skeleton page. The real dashboard pages
        (Jobs Feed, Profile, Preferences, Models/Benchmarks, ...) start in
        P1-d onward.
      </p>
      <h2>Backend health check</h2>
      {error && <p style={{ color: "crimson" }}>Error reaching backend: {error}</p>}
      {health ? (
        <pre>{JSON.stringify(health, null, 2)}</pre>
      ) : (
        !error && <p>Checking backend…</p>
      )}
    </main>
  );
}
