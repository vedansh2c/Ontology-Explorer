import { useEffect, useRef, useState } from 'react';

const initialEvidence = 'Your retrieval trail will appear here with graph facts and answer rationale.';

export default function App() {
  const [ontology, setOntology] = useState(null);
  const [messages, setMessages] = useState([]);
  const [question, setQuestion] = useState('');
  const [evidence, setEvidence] = useState({ label: 'LIVE', message: initialEvidence, facts: '' });
  const [submitting, setSubmitting] = useState(false);
  const conversationEnd = useRef(null);

  useEffect(() => {
    let active = true;

    async function refreshStatus() {
      try {
        const response = await fetch('/api/status');
        if (!response.ok) throw new Error(`Request failed (${response.status})`);
        const status = await response.json();
        if (active) setOntology(status);
      } catch {
        if (active) setOntology({ offline: true, loaded: false, tripleCount: 0 });
      }
    }

    refreshStatus();
    const timer = window.setInterval(refreshStatus, 10000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    conversationEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages]);

  async function handleSubmit(event) {
    event.preventDefault();
    const prompt = question.trim();
    if (!prompt || submitting) return;

    const id = `${Date.now()}-${Math.random()}`;
    setSubmitting(true);
    setQuestion('');
    setEvidence({ label: 'PROCESSING', message: 'Searching the ontology store...', facts: '' });
    setMessages((current) => [...current, {
      id,
      question: prompt,
      answer: 'Waiting for retrieval...',
      activity: [],
      error: false,
    }]);

    try {
      const response = await fetch('/api/question', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: prompt }),
      });
      if (!response.ok) throw new Error(`Request failed (${response.status})`);
      if (!response.body) throw new Error('The server did not return a response stream.');

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let answerReceived = false;
      let evidenceReceived = false;

      function handleEvent(eventText) {
        const data = eventText.split('\n').find((line) => line.startsWith('data:'));
        if (!data) return;
        const payload = JSON.parse(data.slice(5).trim());

        if (payload.type === 'answer') {
          answerReceived = true;
          setMessages((current) => current.map((message) => message.id === id
            ? { ...message, answer: payload.message }
            : message));
          setEvidence((current) => ({ ...current, label: 'READY', message: 'Evidence supplied to the agent.' }));
        }

        if (payload.type === 'evidence') {
          evidenceReceived = true;
          setEvidence({ label: 'FACTS', message: 'Retrieved evidence with graph links and source identifiers.', facts: payload.message });
        }

        if (payload.type === 'status' || payload.type === 'rationale') {
          setMessages((current) => current.map((message) => message.id === id
            ? { ...message, activity: [...message.activity, payload.message] }
            : message));
          if (!evidenceReceived) setEvidence({ label: 'PROCESSING', message: payload.message, facts: '' });
        }
      }

      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
        const events = buffer.split(/\r?\n\r?\n/);
        buffer = events.pop() ?? '';
        events.forEach(handleEvent);
        if (done) break;
      }

      if (buffer.trim()) handleEvent(buffer);
      if (!answerReceived) throw new Error('The server closed the stream before returning an answer.');
      setEvidence((current) => ({ ...current, label: 'READY', message: 'Evidence supplied to the agent.' }));
    } catch (error) {
      const message = error instanceof Error ? error.message : 'An unexpected error occurred.';
      setMessages((current) => current.map((item) => item.id === id
        ? {
          ...item,
          answer: `Unable to complete the question: ${message}`,
          activity: [...item.activity, message],
          error: true,
        }
        : item));
      setEvidence({ label: 'ERROR', message: 'The ontology question request failed.', facts: '' });
    } finally {
      setSubmitting(false);
    }
  }

  const statusLabel = ontology?.offline
    ? 'Offline'
    : ontology?.loaded
      ? 'Ontology ready'
      : ontology
        ? 'Index needed'
        : 'Connecting';
  const sourceLabel = ontology?.loaded
    ? `${ontology.tripleCount.toLocaleString()} triples loaded`
    : ontology?.offline
      ? 'Backend unavailable'
      : 'Ontology source not loaded';

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <span className="eyebrow">RIGOR / ONTOLOGY ATLAS</span>
          <h1>Ask the graph.</h1>
        </div>
        <div className={`status ${ontology?.offline ? 'is-offline' : ''}`} role="status">
          <span className="dot" />{statusLabel}
        </div>
      </header>

      <section className="workspace">
        <div className="conversation" aria-live="polite">
          {messages.length === 0 ? (
            <div className="welcome">
              <span className="mark" aria-hidden="true">◎</span>
              <p>Ask a precise question about the ontology. Answers will be grounded in indexed entities, relationships, and provenance.</p>
            </div>
          ) : messages.map((message) => (
            <article className="exchange" key={message.id}>
              <div className="message">
                <span className="eyebrow">YOU</span>
                <p>{message.question}</p>
              </div>
              <div className={`message answer ${message.error ? 'has-error' : ''}`}>
                <span className="eyebrow">ATLAS</span>
                <div className="activity" aria-live="polite">
                  <span className="activity-title">LIVE WORKING TRACE</span>
                  {message.activity.length > 0 && (
                    <div className="activity-items">
                      {message.activity.map((item, index) => (
                        <div className="activity-item" key={`${message.id}-activity-${index}`}>
                          <span className="activity-dot" />{item}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <p className="answer-text">{message.answer}</p>
              </div>
            </article>
          ))}
          <div ref={conversationEnd} />
        </div>

        <aside className="evidence" aria-label="Evidence record">
          <div className="panel-heading">
            <span>Evidence record</span>
            <span className="muted">{evidence.label}</span>
          </div>
          <div className="empty-evidence">{evidence.message}</div>
          {evidence.facts && <pre className="evidence-facts">{evidence.facts}</pre>}
        </aside>
      </section>

      <form className="composer" onSubmit={handleSubmit}>
        <label className="sr-only" htmlFor="question">Your ontology question</label>
        <textarea
          id="question"
          rows="2"
          placeholder="Ask about a class, relationship, restriction, or provenance record..."
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          required
        />
        <button type="submit" disabled={submitting || !question.trim()}>
          {submitting ? 'Asking…' : <>Ask <span aria-hidden="true">↗</span></>}
        </button>
      </form>

      <footer>
        <span>LOCAL WORKSPACE</span>
        <span>{sourceLabel}</span>
      </footer>
    </main>
  );
}
