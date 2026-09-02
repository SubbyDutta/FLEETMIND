import { useState } from 'react'
import { login } from '../api'
import { saveAuth } from '../auth'

export default function Login({ onLoggedIn }) {
  const [email, setEmail] = useState('dispatcher@acme.com')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const auth = { ...(await login(email.trim(), password)), email: email.trim() }
      saveAuth(auth)
      onLoggedIn(auth)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="login__card" onSubmit={submit}>
        <div className="login__brand">
          <span className="topbar__mark" />
          <span className="topbar__name">FleetMind</span>
        </div>
        <div className="login__sub">Sign in to the ops console</div>
        <label className="login__label">
          Email
          <input
            className="login__input"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label className="login__label">
          Password
          <input
            className="login__input"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        {error && <div className="login__error">{error}</div>}
        <button className="login__btn" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
