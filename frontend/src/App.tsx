import { FormEvent, useCallback, useEffect, useState } from 'react'

import { AdminPortal } from './AdminPortal'
import { ApiError, currentAccount, login, logout as logoutSession, Role } from './api'
import { CustomerPortal } from './CustomerPortal'
import { errorMessage } from './utils'

type Session = { email: string; role: Role }

function Login({ onAuthenticated }: { onAuthenticated: (session: Session) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    const form = event.currentTarget
    const data = new FormData(form)
    const email = data.get('email')
    const password = data.get('password')
    if (typeof email !== 'string' || typeof password !== 'string') {
      setError('The sign-in form is invalid. Please try again.')
      setBusy(false)
      return
    }
    const passwordField = form.elements.namedItem('password')
    if (passwordField instanceof HTMLInputElement) passwordField.value = ''
    try {
      const result = await login(email, password)
      if (result.account.role !== 'CUSTOMER' && result.account.role !== 'ADMIN') {
        throw new ApiError('This account role is not supported.', 403, 'ACCESS_DENIED')
      }
      form.reset()
      onAuthenticated({
        email: result.account.email,
        role: result.account.role,
      })
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="shell login-shell">
      <section className="login-card" aria-labelledby="page-title">
        <p className="eyebrow">Lockstep Tickets</p>
        <h1 id="page-title">Find your next seat.</h1>
        <p className="summary">Sign in to book tickets or administer events.</p>
        <form className="login-form" onSubmit={(event) => void submit(event)}>
          <label htmlFor="email">Email</label>
          <input id="email" name="email" type="email" autoComplete="username" required maxLength={320} />
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="current-password" required maxLength={200} />
          {error && <p className="error" role="alert">{error}</p>}
          <button className="primary" disabled={busy} type="submit">{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <p className="privacy-note">Your password is sent only to sign in. Your session uses a protected, JavaScript-inaccessible cookie.</p>
      </section>
    </main>
  )
}

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [restoring, setRestoring] = useState(() => document.cookie.includes('lockstep_csrf='))

  useEffect(() => {
    if (!restoring) return
    let active = true
    currentAccount().then((account) => {
      if (!active) return
      if (account.role !== 'CUSTOMER' && account.role !== 'ADMIN') {
        throw new ApiError('This account role is not supported.', 403, 'ACCESS_DENIED')
      }
      setSession({ email: account.email, role: account.role })
    }).catch(() => {
      if (active) setSession(null)
    }).finally(() => {
      if (active) setRestoring(false)
    })
    return () => { active = false }
  }, [restoring])

  const logout = useCallback(() => {
    void logoutSession().catch(() => undefined).finally(() => setSession(null))
  }, [])

  if (restoring) {
    return <main className="shell login-shell"><p role="status">Restoring your secure session…</p></main>
  }
  if (!session) return <Login onAuthenticated={setSession} />
  return (
    <main className="app-shell">
      <header className="topbar">
        <div><p className="eyebrow">Lockstep Tickets</p><span className="signed-in">{session.role === 'ADMIN' ? 'Administrator' : 'Customer'} · {session.email}</span></div>
        <button className="quiet" type="button" onClick={logout}>Sign out</button>
      </header>
      {session.role === 'ADMIN'
        ? <AdminPortal onLogout={logout} />
        : <CustomerPortal onLogout={logout} />}
    </main>
  )
}
