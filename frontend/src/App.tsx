import { FormEvent, useCallback, useEffect, useState } from 'react'

import { ApiError, EventDetail, EventSummary, getEvent, listEvents, login, Money } from './api'

type Session = { token: string; email: string }

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const reference = error.requestId ? ` Reference: ${error.requestId}.` : ''
    return `${error.message}${reference}`
  }
  return 'We could not reach the ticket service. Please try again.'
}

function formatMoney(money: Money | null) {
  if (!money) return 'Pricing to be announced'
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: money.currency,
  }).format(money.amountMinor / 100)
}

function formatDate(value: string, timeZone: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'full',
    timeStyle: 'short',
    timeZone,
  }).format(new Date(value))
}

function Login({ onAuthenticated }: { onAuthenticated: (session: Session) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
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
      form.reset()
      onAuthenticated({ token: result.accessToken, email: result.account.email })
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
        <p className="summary">Sign in to browse events and live seat availability.</p>
        <form className="login-form" onSubmit={(event) => void submit(event)}>
          <label htmlFor="email">Email</label>
          <input id="email" name="email" type="email" autoComplete="username" required maxLength={320} />
          <label htmlFor="password">Password</label>
          <input id="password" name="password" type="password" autoComplete="current-password" required maxLength={200} />
          {error && <p className="error" role="alert">{error}</p>}
          <button className="primary" disabled={busy} type="submit">
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="privacy-note">Your password is sent only to sign in and is never stored in this browser.</p>
      </section>
    </main>
  )
}

function Catalog({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [events, setEvents] = useState<EventSummary[]>([])
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<EventDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const operation = selectedId
      ? getEvent(session.token, selectedId, controller.signal)
      : listEvents(session.token, submittedQuery, controller.signal)

    operation
      .then((result) => {
        if ('seats' in result) setDetail(result)
        else setEvents(result.items)
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return
        if (cause instanceof ApiError && cause.status === 401) {
          onLogout()
          return
        }
        setError(errorMessage(cause))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => controller.abort()
  }, [onLogout, retryKey, selectedId, session.token, submittedQuery])

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoading(true)
    setError('')
    setSelectedId(null)
    setDetail(null)
    setSubmittedQuery(query.trim())
  }

  function retry() {
    setLoading(true)
    setError('')
    setRetryKey((value) => value + 1)
  }

  function showCatalog() {
    setLoading(true)
    setError('')
    setSelectedId(null)
    setDetail(null)
  }

  function showEvent(eventId: string) {
    setLoading(true)
    setError('')
    setDetail(null)
    setSelectedId(eventId)
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Lockstep Tickets</p>
          <span className="signed-in">Signed in as {session.email}</span>
        </div>
        <button className="quiet" type="button" onClick={onLogout}>Sign out</button>
      </header>

      {selectedId ? (
        <section className="content" aria-labelledby="detail-heading">
          <button className="back" type="button" onClick={showCatalog}>
            ← Back to events
          </button>
          {loading && <p role="status">Loading seats…</p>}
          {error && <ErrorPanel message={error} retry={retry} />}
          {detail && !loading && !error && <EventDetails event={detail} />}
        </section>
      ) : (
        <section className="content" aria-labelledby="catalog-heading">
          <div className="catalog-heading">
            <div>
              <p className="eyebrow">Upcoming</p>
              <h1 id="catalog-heading">Choose an event.</h1>
            </div>
            <form className="search" role="search" onSubmit={search}>
              <label className="sr-only" htmlFor="event-search">Search events</label>
              <input id="event-search" value={query} maxLength={100} onChange={(event) => setQuery(event.target.value)} placeholder="Search by event name" />
              <button type="submit">Search</button>
            </form>
          </div>
          {loading && <p role="status">Loading events…</p>}
          {error && <ErrorPanel message={error} retry={retry} />}
          {!loading && !error && events.length === 0 && <p className="empty">No events match your search.</p>}
          {!loading && !error && events.length > 0 && (
            <div className="event-grid">
              {events.map((event) => (
                <article className="event-card" key={event.id}>
                  <div className="event-state">{event.state.replace('_', ' ')}</div>
                  <h2>{event.name}</h2>
                  <p>{event.venue.name}</p>
                  <time dateTime={event.startsAt}>{formatDate(event.startsAt, event.timeZone)}</time>
                  <div className="card-footer">
                    <span>From {formatMoney(event.fromPrice)}</span>
                    <button type="button" onClick={() => showEvent(event.id)}>View seats</button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      )}
    </main>
  )
}

function ErrorPanel({ message, retry }: { message: string; retry: () => void }) {
  return (
    <div className="error-panel" role="alert">
      <p>{message}</p>
      <button type="button" onClick={retry}>Try again</button>
    </div>
  )
}

function EventDetails({ event }: { event: EventDetail }) {
  return (
    <article>
      <p className="eyebrow">{event.state.replace('_', ' ')}</p>
      <h1 id="detail-heading">{event.name}</h1>
      <p className="detail-meta">{formatDate(event.startsAt, event.timeZone)} · {event.venue.name}</p>
      <p className="detail-meta">{event.venue.address}</p>
      <div className="legend" aria-label="Seat availability legend">
        <span><i className="available-swatch" /> Available</span>
        <span><i className="unavailable-swatch" /> Unavailable</span>
      </div>
      {event.seats.length === 0 ? (
        <p className="empty">The seating plan has not been published yet.</p>
      ) : (
        <div className="seat-grid" aria-label="Seats">
          {event.seats.map((seat) => {
            const available = seat.availability === 'AVAILABLE'
            const label = `${seat.sectionName}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${seat.availability.toLowerCase()}`
            return (
              <button className="seat" key={seat.id} type="button" disabled={!available} aria-label={label} title={`${seat.priceTier} · ${formatMoney(seat.price)}`}>
                <span>{seat.rowLabel}{seat.seatNumber}</span>
                <small>{formatMoney(seat.price)}</small>
                {seat.accessible && <small>Accessible</small>}
              </button>
            )
          })}
        </div>
      )}
      <p className="fee-note">Service fee: {formatMoney(event.serviceFee)} per ticket. Seat selection and checkout arrive in the next checkpoint.</p>
    </article>
  )
}

export function App() {
  const [session, setSession] = useState<Session | null>(null)
  const logout = useCallback(() => setSession(null), [])
  if (!session) return <Login onAuthenticated={setSession} />
  return <Catalog session={session} onLogout={logout} />
}
