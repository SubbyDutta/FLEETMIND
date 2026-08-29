import { useEffect, useRef, useState } from 'react'
import { askAgent, askAnalytics } from '../api'

// The ops assistant, promoted out of the sidebar into its own console:
// a floating launcher orb opens a full-height drawer over the map.
// Dispatch mode streams one finite SSE agent episode (tool steps render live
// on a timeline); analytics mode is one POST -> one JSON whose tools_used
// render as an already-completed timeline. The drawer stays MOUNTED when
// closed (CSS slide only) so closing mid-episode never kills the SSE stream
// by unmounting — the episode keeps streaming and the orb pulses "busy".

const MODES = {
  dispatch: {
    label: 'Dispatch',
    thinking: 'reasoning over the fleet…',
    placeholder: 'Ask the dispatch agent…',
    chips: ['what’s wrong with order o42?', 'is driver-3 actually moving?'],
  },
  analytics: {
    label: 'Analytics',
    thinking: 'crunching the numbers…',
    placeholder: 'Ask the analytics agent…',
    chips: [
      'how many SLA breaches in Park Street in the last hour?',
      'what’s fleet utilization right now?',
      'how are ETAs looking?',
    ],
  },
}

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
  const running = !msg.answer && !msg.error
  return (
    <div className="ac-msg ac-msg--agent">
      <span className="ac-msg__badge">{MODES[msg.mode].label} agent</span>
      {msg.steps.length > 0 && <StepList steps={msg.steps} />}
      {running && (
        <div className="ac-thinking">
          <span className="ac-dot" /><span className="ac-dot" /><span className="ac-dot" />
          <span className="ac-thinking__label">{MODES[msg.mode].thinking}</span>
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
  const [messages, setMessages] = useState([])
  const [busy, setBusy] = useState(false)
  const logRef = useRef(null)
  const cancelRef = useRef(null)
  const inputRef = useRef(null)

  const scrollDown = () => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
  }
  useEffect(scrollDown, [messages, busy])

  // Focus the input when the drawer opens; Escape closes it.
  useEffect(() => {
    if (open) inputRef.current?.focus()
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  // App unmount mid-episode: close the SSE stream so the backend stops the loop.
  useEffect(() => () => cancelRef.current?.(), [])

  // Every stream event mutates the last message (the running agent episode).
  const patchEpisode = (fn) =>
    setMessages((ms) => ms.map((m, i) => (i === ms.length - 1 ? fn(m) : m)))

  const ask = (q) => {
    if (!q || busy) return
    setText('')
    setBusy(true)
    setMessages((ms) => [
      ...ms,
      { role: 'user', text: q },
      { role: 'agent', mode, steps: [], answer: null, error: null },
    ])

    if (mode === 'analytics') {
      // One shot, no stream: the tools the agent used render as done steps.
      askAnalytics(q)
        .then(({ answer, tools_used }) => patchEpisode((m) => ({
          ...m,
          steps: (tools_used || []).map((tool) => ({ tool, args: null, result: {} })),
          answer,
        })))
        .catch((err) => patchEpisode((m) => ({ ...m, error: err.message })))
        .finally(() => setBusy(false))
      return
    }

    cancelRef.current = askAgent(q, {
      onStep: ({ type, tool, payload }) => patchEpisode((m) => {
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
        setBusy(false)
        patchEpisode((m) => ({ ...m, answer: payload.answer }))
      },
      onError: (payload) => {
        setBusy(false)
        patchEpisode((m) => ({ ...m, error: payload.error || 'agent failed' }))
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
        className={`ac-fab${busy ? ' ac-fab--busy' : ''}${open ? ' ac-fab--hidden' : ''}`}
        onClick={() => setOpen(true)}
        aria-label="Open ops assistant"
      >
        <span className="ac-fab__icon">✦</span>
      </button>

      <div
        className={`ac-backdrop${open ? ' ac-backdrop--open' : ''}`}
        onClick={() => setOpen(false)}
      />

      <section
        className={`ac-drawer${open ? ' ac-drawer--open' : ''}${busy ? ' ac-drawer--busy' : ''}`}
        role="dialog"
        aria-label="FleetMind assistant"
      >
        <div className="ac-scan" />

        <header className="ac-head">
          <div className="ac-head__id">
            <h2 className="ac-title">FleetMind Assistant</h2>
            <p className="ac-sub">{busy ? MODES[mode].thinking : 'dispatch actions · fleet analytics'}</p>
          </div>
          <div className="ac-tabs" role="tablist">
            {Object.entries(MODES).map(([key, m]) => (
              <button
                key={key}
                type="button"
                role="tab"
                aria-selected={mode === key}
                className={`ac-tab${mode === key ? ' ac-tab--on' : ''}`}
                onClick={() => setMode(key)}
                disabled={busy}
              >
                {m.label}
              </button>
            ))}
          </div>
          <button type="button" className="ac-close" onClick={() => setOpen(false)} aria-label="Close">✕</button>
        </header>

        <div className="ac-log" ref={logRef}>
          {messages.length === 0 ? (
            <div className="ac-empty">
              <span className="ac-empty__orb">✦</span>
              <p className="ac-empty__title">Ask the fleet anything</p>
              <p className="ac-empty__hint">
                {mode === 'dispatch'
                  ? 'The dispatch agent investigates incidents and acts by the runbooks.'
                  : 'The analytics agent answers with live numbers from the fleet database.'}
              </p>
              <div className="ac-chips">
                {MODES[mode].chips.map((c) => (
                  <button key={c} type="button" className="ac-chip" onClick={() => ask(c)}>
                    {c}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((m, i) =>
              m.role === 'user'
                ? <div key={i} className="ac-msg ac-msg--user">{m.text}</div>
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
            placeholder={busy ? 'Agent is working…' : MODES[mode].placeholder}
            disabled={busy}
          />
          <button className="ac-send" type="submit" disabled={busy || !text.trim()} aria-label="Send">
            ➤
          </button>
        </form>
      </section>
    </>
  )
}
