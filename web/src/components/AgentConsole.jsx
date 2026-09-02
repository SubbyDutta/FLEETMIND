import { useEffect, useRef, useState } from 'react'
import { askAgent, askAnalytics } from '../api'

// The ops assistant: a floating launcher opens a full-height drawer over the
// map, holding TWO independent chat consoles — the dispatch agent (streams one
// finite SSE episode; tool steps render live on a timeline) and the analytics
// agent (one POST -> one JSON; tools_used render as a completed timeline).
// Each console keeps its own history, accent color and busy state, so a
// dispatch episode can keep streaming while you query analytics next to it.
// The drawer stays MOUNTED when closed (CSS slide only) so closing mid-episode
// never kills the SSE stream by unmounting — the launcher pulses "busy".

const ICONS = {
  // lucide "route": waypoints — dispatch moves things
  dispatch: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="19" r="3" /><circle cx="18" cy="5" r="3" />
      <path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15" />
    </svg>
  ),
  // lucide "bar-chart-3" — analytics counts things
  analytics: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 3v18h18" /><path d="M18 17V9" /><path d="M13 17V5" /><path d="M8 17v-3" />
    </svg>
  ),
  chat: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z" />
      <path d="M18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1" />
    </svg>
  ),
  send: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="m22 2-7 20-4-9-9-4Z" /><path d="M22 2 11 13" />
    </svg>
  ),
}

const MODES = {
  dispatch: {
    label: 'Dispatch',
    title: 'Dispatch Agent',
    desc: 'agentic tool loop · command-service · SSE',
    accent: '#2563eb',
    accentSoft: 'rgba(37, 99, 235, 0.10)',
    thinking: 'running tool loop…',
    placeholder: 'Ask the dispatch agent…',
    hint: 'Investigates incidents against live fleet state and acts by the runbooks — every tool call shows up below as it happens.',
    chips: ['what’s wrong with order o42?', 'is driver-3 actually moving?'],
  },
  analytics: {
    label: 'Analytics',
    title: 'Analytics Agent',
    desc: 'aggregate queries · fleet read model',
    accent: '#7c3aed',
    accentSoft: 'rgba(124, 58, 237, 0.10)',
    thinking: 'crunching the numbers…',
    placeholder: 'Ask the analytics agent…',
    hint: 'Answers with live numbers computed from the fleet read model — utilization, SLA breaches, ETA health.',
    chips: [
      'how many SLA breaches in Park Street in the last hour?',
      'what’s fleet utilization right now?',
      'how are ETAs looking?',
    ],
  },
}

const fmtTime = (ts) =>
  new Date(ts).toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })

const argsSummary = (args) =>
  args && Object.keys(args).length
    ? Object.entries(args).map(([k, v]) => `${k}: ${String(v)}`).join(' · ')
    : ''

// A tool_result is a failure if the tool reported an error or refused to act.
const stepStatus = (s) =>
  s.result === undefined ? 'pending'
    : s.result.error || s.result.success === false ? 'fail'
    : 'ok'

function StepList({ steps }) {
  return (
    <ol className="ac-steps">
      {steps.map((s, i) => (
        <li
          key={i}
          className={`ac-step ac-step--${stepStatus(s)}`}
          style={{ '--d': `${Math.min(i, 6) * 80}ms` }}
        >
          <span className="ac-step__node" />
          <div className="ac-step__body">
            <span className="ac-step__tool">{s.tool}</span>
            {argsSummary(s.args) && <span className="ac-step__args">{argsSummary(s.args)}</span>}
          </div>
        </li>
      ))}
    </ol>
  )
}

// Types the newest answer out character by character; old answers render flat.
function AnswerText({ text, animate, onGrow }) {
  const [n, setN] = useState(animate ? 0 : text.length)
  useEffect(() => {
    if (!animate) { setN(text.length); return }
    const id = setInterval(() => {
      setN((prev) => {
        const next = Math.min(text.length, prev + 3)
        if (next >= text.length) clearInterval(id)
        return next
      })
      onGrow?.()
    }, 18)
    return () => clearInterval(id)
  }, [text, animate]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <p className="ac-answer">
      {text.slice(0, n)}
      {n < text.length && <span className="ac-caret" />}
    </p>
  )
}

function AgentMessage({ msg, live, onGrow }) {
  const m = MODES[msg.mode]
  const running = !msg.answer && !msg.error
  return (
    <div className="ac-msg ac-msg--agent">
      <div className="ac-msg__head">
        <span className="ac-msg__avatar">{ICONS[msg.mode]}</span>
        <span className="ac-msg__badge">{m.title}</span>
        <span className="ac-msg__time">{fmtTime(msg.ts)}</span>
      </div>
      {msg.steps.length > 0 && <StepList steps={msg.steps} />}
      {running && (
        <div className="ac-thinking">
          <span className="ac-dot" /><span className="ac-dot" /><span className="ac-dot" />
          <span className="ac-thinking__label">{m.thinking}</span>
        </div>
      )}
      {msg.answer && <AnswerText text={msg.answer} animate={live} onGrow={onGrow} />}
      {msg.error && <p className="ac-answer ac-answer--error">⚠ {msg.error}</p>}
    </div>
  )
}

export default function AgentConsole() {
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState('dispatch')
  const [text, setText] = useState('')
  // One history and one busy flag PER console — they are separate chats.
  const [logs, setLogs] = useState({ dispatch: [], analytics: [] })
  const [busy, setBusy] = useState({ dispatch: false, analytics: false })
  const logRef = useRef(null)
  const cancelRef = useRef(null)
  const inputRef = useRef(null)

  const anyBusy = busy.dispatch || busy.analytics
  const messages = logs[mode]
  const M = MODES[mode]

  const scrollDown = () => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
  }
  useEffect(scrollDown, [logs, busy, mode])

  // Focus the input when the drawer opens or the console switches; Esc closes.
  useEffect(() => {
    if (open) inputRef.current?.focus()
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, mode])

  // App unmount mid-episode: close the SSE stream so the backend stops the loop.
  useEffect(() => () => cancelRef.current?.(), [])

  // Every stream event mutates the last message of ITS OWN console's log —
  // the running episode — even if the user has flipped to the other console.
  const patchEpisode = (which, fn) =>
    setLogs((ls) => ({
      ...ls,
      [which]: ls[which].map((m, i) => (i === ls[which].length - 1 ? fn(m) : m)),
    }))

  const setModeBusy = (which, val) => setBusy((b) => ({ ...b, [which]: val }))

  const ask = (q) => {
    const which = mode
    if (!q || busy[which]) return
    setText('')
    setModeBusy(which, true)
    setLogs((ls) => ({
      ...ls,
      [which]: [
        ...ls[which],
        { role: 'user', text: q, ts: Date.now() },
        { role: 'agent', mode: which, steps: [], answer: null, error: null, ts: Date.now() },
      ],
    }))

    if (which === 'analytics') {
      // One shot, no stream: the tools the agent used render as done steps.
      askAnalytics(q)
        .then(({ answer, tools_used }) => patchEpisode(which, (m) => ({
          ...m,
          steps: (tools_used || []).map((tool) => ({ tool, args: null, result: {} })),
          answer,
        })))
        .catch((err) => patchEpisode(which, (m) => ({ ...m, error: err.message })))
        .finally(() => setModeBusy(which, false))
      return
    }

    cancelRef.current = askAgent(q, {
      onStep: ({ type, tool, payload }) => patchEpisode(which, (m) => {
        const steps = [...m.steps]
        if (type === 'tool_call') {
          steps.push({ tool, args: payload?.args, result: undefined })
        } else {
          // Pair the result with its still-pending call (same tool, oldest first).
          const i = steps.findIndex((s) => s.tool === tool && s.result === undefined)
          if (i >= 0) steps[i] = { ...steps[i], result: payload }
        }
        return { ...m, steps }
      }),
      onFinal: (payload) => {
        setModeBusy(which, false)
        patchEpisode(which, (m) => ({ ...m, answer: payload.answer }))
      },
      onError: (payload) => {
        setModeBusy(which, false)
        patchEpisode(which, (m) => ({ ...m, error: payload.error || 'agent failed' }))
      },
    })
  }

  const submit = (e) => {
    e.preventDefault()
    ask(text.trim())
  }

  const lastAgentIdx = messages.reduce((acc, m, i) => (m.role === 'agent' ? i : acc), -1)

  return (
    <>
      <button
        type="button"
        className={`ac-fab${anyBusy ? ' ac-fab--busy' : ''}${open ? ' ac-fab--hidden' : ''}`}
        onClick={() => setOpen(true)}
        aria-label="Open ops assistant"
      >
        <span className="ac-fab__icon">{ICONS.chat}</span>
      </button>

      <div
        className={`ac-backdrop${open ? ' ac-backdrop--open' : ''}`}
        onClick={() => setOpen(false)}
      />

      <section
        className={`ac-drawer${open ? ' ac-drawer--open' : ''}${busy[mode] ? ' ac-drawer--busy' : ''}`}
        style={{ '--ac': M.accent, '--ac-soft': M.accentSoft }}
        role="dialog"
        aria-label="FleetMind agents"
      >
        <div className="ac-scan" />

        <header className="ac-head">
          <span className="ac-head__tile">{ICONS[mode]}</span>
          <div className="ac-head__id">
            <h2 className="ac-title">{M.title}</h2>
            <p className="ac-sub">{busy[mode] ? M.thinking : M.desc}</p>
          </div>
          <button type="button" className="ac-close" onClick={() => setOpen(false)} aria-label="Close">✕</button>
        </header>

        <div className="ac-tabs" role="tablist">
          {Object.entries(MODES).map(([key, m]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={mode === key}
              className={`ac-tab${mode === key ? ' ac-tab--on' : ''}`}
              style={{ '--tac': m.accent, '--tac-soft': m.accentSoft }}
              onClick={() => setMode(key)}
            >
              <span className="ac-tab__icon">{ICONS[key]}</span>
              {m.label}
              {logs[key].length > 0 && !busy[key] && (
                <span className="ac-tab__count">{logs[key].filter((x) => x.role === 'user').length}</span>
              )}
              {busy[key] && <span className="ac-tab__busy" aria-label="working" />}
            </button>
          ))}
        </div>

        {/* Keyed by mode: switching consoles swaps the whole pane with a slide. */}
        <div className="ac-pane" key={mode}>
          <div className="ac-log" ref={logRef}>
            {messages.length === 0 ? (
              <div className="ac-empty">
                <span className="ac-empty__tile">{ICONS[mode]}</span>
                <p className="ac-empty__title">{M.title}</p>
                <p className="ac-empty__hint">{M.hint}</p>
                <div className="ac-chips">
                  {M.chips.map((c) => (
                    <button key={c} type="button" className="ac-chip" onClick={() => ask(c)}>
                      {c}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((m, i) =>
                m.role === 'user'
                  ? (
                    <div key={i} className="ac-row ac-row--user">
                      <div className="ac-msg ac-msg--user">{m.text}</div>
                      <span className="ac-msg__time ac-msg__time--user">{fmtTime(m.ts)}</span>
                    </div>
                  )
                  : <AgentMessage key={i} msg={m} live={i === lastAgentIdx} onGrow={scrollDown} />
              )
            )}
          </div>

          <form className="ac-form" onSubmit={submit}>
            <input
              ref={inputRef}
              className="ac-input"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={busy[mode] ? 'Agent is working…' : M.placeholder}
              disabled={busy[mode]}
            />
            <button className="ac-send" type="submit" disabled={busy[mode] || !text.trim()} aria-label="Send">
              {ICONS.send}
            </button>
          </form>
        </div>
      </section>
    </>
  )
}
