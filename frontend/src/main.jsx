import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

function App() {
  const [status, setStatus] = useState('Checking backend…');
  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/health', { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('Health request failed');
        const body = await response.json();
        if (body.status !== 'UP' || body.service !== 'backend') throw new Error('Unexpected health response');
        setStatus('Backend: UP');
      })
      .catch(error => { if (error.name !== 'AbortError') setStatus('Backend unavailable. Check the backend terminal.'); });
    return () => controller.abort();
  }, []);
  return <main><h1>FinSight + BankPilot</h1><p>Foundation milestone</p><p role="status">{status}</p><p>Financial and forecasting features are not implemented.</p></main>;
}
createRoot(document.getElementById('root')).render(<App />);
