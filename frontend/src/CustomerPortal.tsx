import { FormEvent, ReactNode, useCallback, useEffect, useState } from 'react'

import {
  ApiError,
  Booking,
  BookingDetail,
  cancelTickets,
  cancelWaitlist,
  checkout,
  claimWaitlist,
  createHold,
  EventDetail,
  EventSummary,
  getBooking,
  getEvent,
  Hold,
  joinWaitlist,
  listBookings,
  listEvents,
  listWaitlist,
  reconcileBooking,
  reconcileRefund,
  Refund,
  retryRefund,
  WaitlistClaim,
  WaitlistEntry,
} from './api'
import { ErrorPanel, Loading, StatusBadge, SuccessPanel } from './ui'
import { errorMessage, formText, formatDate, formatMinor, isAbort, newIdempotencyKey } from './utils'

type Props = { token: string; onLogout: () => void }
type Tab = 'events' | 'bookings' | 'waitlist'

function unauthorized(error: unknown, onLogout: () => void) {
  if (error instanceof ApiError && error.status === 401) {
    onLogout()
    return true
  }
  return false
}

export function CustomerPortal({ token, onLogout }: Props) {
  const [tab, setTab] = useState<Tab>('events')
  return (
    <>
      <nav className="tabs" aria-label="Customer tools">
        {(['events', 'bookings', 'waitlist'] as const).map((item) => (
          <button key={item} className={tab === item ? 'active' : ''} type="button" aria-current={tab === item ? 'page' : undefined} onClick={() => setTab(item)}>
            {item === 'events' ? 'Events' : item === 'bookings' ? 'My bookings' : 'My waitlist'}
          </button>
        ))}
      </nav>
      {tab === 'events' && <EventBrowser token={token} onLogout={onLogout} onBooked={() => setTab('bookings')} />}
      {tab === 'bookings' && <Bookings token={token} onLogout={onLogout} />}
      {tab === 'waitlist' && <Waitlist token={token} onLogout={onLogout} onBooked={() => setTab('bookings')} />}
    </>
  )
}

function EventBrowser({ token, onLogout, onBooked }: Props & { onBooked: () => void }) {
  const [events, setEvents] = useState<EventSummary[]>([])
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<EventDetail | null>(null)
  const [selectedSeats, setSelectedSeats] = useState<string[]>([])
  const [hold, setHold] = useState<Hold | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const operation = selectedId
      ? getEvent(token, selectedId, controller.signal)
      : listEvents(token, submittedQuery, controller.signal)
    operation.then((result) => {
      if ('seats' in result) setDetail(result)
      else setEvents(result.items)
    }).catch((cause: unknown) => {
      if (isAbort(cause) || unauthorized(cause, onLogout)) return
      setError(errorMessage(cause))
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [onLogout, retryKey, selectedId, submittedQuery, token])

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoading(true)
    setError('')
    setSelectedId(null)
    setDetail(null)
    setSelectedSeats([])
    setHold(null)
    setSubmittedQuery(query.trim())
  }

  function openEvent(id: string) {
    setLoading(true)
    setError('')
    setDetail(null)
    setSelectedSeats([])
    setHold(null)
    setSuccess('')
    setSelectedId(id)
  }

  function back() {
    setLoading(true)
    setError('')
    setSelectedId(null)
    setDetail(null)
    setSelectedSeats([])
    setHold(null)
    setSuccess('')
  }

  function toggleSeat(id: string) {
    setError('')
    setSelectedSeats((current) => {
      if (current.includes(id)) return current.filter((seatId) => seatId !== id)
      if (current.length === 6) {
        setError('An order can contain at most six seats.')
        return current
      }
      return [...current, id]
    })
  }

  async function holdSeats(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail || selectedSeats.length === 0 || busy) return
    setBusy(true)
    setError('')
    setSuccess('')
    const data = new FormData(event.currentTarget)
    try {
      const result = await createHold(token, detail.id, selectedSeats, formText(data, 'promoCode'))
      setHold(result)
      setSuccess('Your seats are held. Complete checkout before the server deadline.')
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  async function join(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail || busy) return
    setBusy(true)
    setError('')
    const quantity = Number(new FormData(event.currentTarget).get('quantity'))
    try {
      await joinWaitlist(token, detail.id, quantity)
      setSuccess('You joined the waitlist. Offers must be claimed within 15 minutes.')
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="content" aria-labelledby={selectedId ? 'detail-heading' : 'catalog-heading'}>
      {selectedId ? (
        <>
          <button className="back" type="button" onClick={back}>← Back to events</button>
          {loading && <Loading>Loading seats…</Loading>}
          {error && <ErrorPanel message={error} retry={() => { setLoading(true); setError(''); setRetryKey((value) => value + 1) }} />}
          {success && <SuccessPanel>{success}</SuccessPanel>}
          {detail && !loading && (
            <EventDetailView event={detail} selectedSeats={selectedSeats} onToggle={toggleSeat}>
              {hold ? (
                <CheckoutPanel token={token} hold={hold} onLogout={onLogout} onBooked={onBooked} />
              ) : selectedSeats.length > 0 ? (
                <form className="action-card" onSubmit={(event) => void holdSeats(event)}>
                  <h2>Hold {selectedSeats.length} {selectedSeats.length === 1 ? 'seat' : 'seats'}</h2>
                  <label htmlFor="promo-code">Promo code (optional)</label>
                  <input id="promo-code" name="promoCode" maxLength={40} autoComplete="off" />
                  <button className="primary" type="submit" disabled={busy}>{busy ? 'Creating hold…' : 'Hold selected seats'}</button>
                </form>
              ) : detail.seats.every((seat) => seat.availability !== 'AVAILABLE') ? (
                <form className="action-card" onSubmit={(event) => void join(event)}>
                  <h2>Join the waitlist</h2>
                  <p>No seats are currently available. An offer reserves seats for 15 minutes and never purchases automatically.</p>
                  <label htmlFor="waitlist-quantity">Number of seats</label>
                  <input id="waitlist-quantity" name="quantity" type="number" min="1" max="6" defaultValue="1" required />
                  <button className="primary" type="submit" disabled={busy}>{busy ? 'Joining…' : 'Join waitlist'}</button>
                </form>
              ) : <p className="hint">Choose up to six available seats to see a server-calculated quote.</p>}
            </EventDetailView>
          )}
        </>
      ) : (
        <>
          <div className="catalog-heading">
            <div><p className="eyebrow">Upcoming</p><h1 id="catalog-heading">Choose an event.</h1></div>
            <form className="search" role="search" onSubmit={search}>
              <label className="sr-only" htmlFor="event-search">Search events</label>
              <input id="event-search" value={query} maxLength={100} onChange={(event) => setQuery(event.target.value)} placeholder="Search by event name" />
              <button type="submit">Search</button>
            </form>
          </div>
          {loading && <Loading>Loading events…</Loading>}
          {error && <ErrorPanel message={error} retry={() => { setLoading(true); setError(''); setRetryKey((value) => value + 1) }} />}
          {!loading && !error && events.length === 0 && <p className="empty">No events match your search.</p>}
          {!loading && !error && events.length > 0 && <EventCards events={events} onOpen={openEvent} />}
        </>
      )}
    </section>
  )
}

function EventCards({ events, onOpen }: { events: EventSummary[]; onOpen: (id: string) => void }) {
  return (
    <div className="event-grid">
      {events.map((event) => (
        <article className="event-card" key={event.id}>
          <StatusBadge>{event.state.replaceAll('_', ' ')}</StatusBadge>
          <h2>{event.name}</h2>
          <p>{event.venue.name}</p>
          <time dateTime={event.startsAt}>{formatDate(event.startsAt, event.timeZone)}</time>
          <div className="card-footer">
            <span>{event.fromPrice ? `From ${formatMinor(event.fromPrice.amountMinor, event.fromPrice.currency)}` : 'Pricing to be announced'}</span>
            <button type="button" onClick={() => onOpen(event.id)}>View seats</button>
          </div>
        </article>
      ))}
    </div>
  )
}

function EventDetailView({ event, selectedSeats, onToggle, children }: {
  event: EventDetail
  selectedSeats: string[]
  onToggle: (id: string) => void
  children: ReactNode
}) {
  return (
    <article>
      <p className="eyebrow">{event.state.replaceAll('_', ' ')}</p>
      <h1 id="detail-heading">{event.name}</h1>
      <p className="detail-meta">{formatDate(event.startsAt, event.timeZone)} · {event.venue.name}</p>
      <p className="detail-meta">{event.venue.address}</p>
      <div className="legend" aria-label="Seat availability legend">
        <span><i className="available-swatch" /> Available</span>
        <span><i className="selected-swatch" /> Selected</span>
        <span><i className="unavailable-swatch" /> Unavailable</span>
      </div>
      {event.seats.length === 0 ? <p className="empty">The seating plan has not been published yet.</p> : (
        <div className="seat-grid" aria-label="Seats">
          {event.seats.map((seat) => {
            const available = seat.availability === 'AVAILABLE'
            const selected = selectedSeats.includes(seat.id)
            const label = `${seat.sectionName}, row ${seat.rowLabel}, seat ${seat.seatNumber}, ${selected ? 'selected' : seat.availability.toLowerCase()}`
            return (
              <button className={`seat${selected ? ' selected' : ''}`} key={seat.id} type="button" disabled={!available} aria-pressed={available ? selected : undefined} aria-label={label} title={`${seat.priceTier} · ${formatMinor(seat.price.amountMinor, seat.price.currency)}`} onClick={() => onToggle(seat.id)}>
                <span>{seat.rowLabel}{seat.seatNumber}</span>
                <small>{formatMinor(seat.price.amountMinor, seat.price.currency)}</small>
                {seat.accessible && <small>Accessible</small>}
              </button>
            )
          })}
        </div>
      )}
      <p className="fee-note">Service fee: {formatMinor(event.serviceFee.amountMinor, event.serviceFee.currency)} per order.</p>
      {children}
    </article>
  )
}

function CheckoutPanel({ token, hold, onLogout, onBooked }: Props & { hold: Hold | WaitlistClaim; onBooked: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [booking, setBooking] = useState<Booking | null>(null)
  const [key] = useState(newIdempotencyKey)

  async function pay(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    try {
      setBooking(await checkout(token, 'id' in hold ? hold.id : hold.holdId, key))
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  async function reconcile() {
    if (!booking || busy) return
    setBusy(true)
    setError('')
    try {
      setBooking(await reconcileBooking(token, booking.id))
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  const currency = hold.currency
  if (booking) return (
    <div className="action-card" aria-live="polite">
      <h2>Booking {booking.reference}</h2>
      <p><StatusBadge>{booking.state.replaceAll('_', ' ')}</StatusBadge></p>
      <p>Total: {formatMinor(booking.totalMinor, booking.currency)}</p>
      {booking.state === 'PAYMENT_UNKNOWN' && <button type="button" disabled={busy} onClick={() => void reconcile()}>{busy ? 'Checking…' : 'Check payment status'}</button>}
      <button type="button" onClick={onBooked}>View my bookings</button>
      {error && <ErrorPanel message={error} />}
    </div>
  )

  return (
    <form className="action-card" onSubmit={(event) => void pay(event)}>
      <h2>Checkout</h2>
      <dl className="money-summary">
        <div><dt>Subtotal</dt><dd>{formatMinor(hold.subtotalMinor, currency)}</dd></div>
        <div><dt>Discounts</dt><dd>−{formatMinor(hold.discountMinor, currency)}</dd></div>
        <div><dt>Service fee</dt><dd>{formatMinor(hold.serviceFeeMinor, currency)}</dd></div>
        <div className="total"><dt>Total</dt><dd>{formatMinor(hold.totalMinor, currency)}</dd></div>
      </dl>
      <p>Hold expires <time dateTime={hold.expiresAt}>{formatDate(hold.expiresAt)}</time>.</p>
      <button className="primary" type="submit" disabled={busy}>{busy ? 'Processing once…' : 'Complete checkout'}</button>
      <p className="privacy-note">Payments are processed by the server. This demo uses an internal mock provider.</p>
      {error && <ErrorPanel message={error} />}
    </form>
  )
}

function Bookings({ token, onLogout }: Props) {
  const [items, setItems] = useState<Booking[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<BookingDetail | null>(null)
  const [selectedTickets, setSelectedTickets] = useState<string[]>([])
  const [refund, setRefund] = useState<Refund | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey)

  const load = useCallback(() => {
    setLoading(true)
    setError('')
    setRetryKey((value) => value + 1)
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    const operation = selectedId ? getBooking(token, selectedId, controller.signal) : listBookings(token, controller.signal)
    operation.then((result) => {
      if (Array.isArray(result)) setItems(result)
      else setDetail(result)
    }).catch((cause: unknown) => {
      if (isAbort(cause) || unauthorized(cause, onLogout)) return
      setError(errorMessage(cause))
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [onLogout, retryKey, selectedId, token])

  function open(id: string) {
    setLoading(true)
    setError('')
    setSelectedId(id)
    setDetail(null)
    setRefund(null)
    setSelectedTickets([])
    setIdempotencyKey(newIdempotencyKey())
  }

  async function cancel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!detail || selectedTickets.length === 0 || busy) return
    setBusy(true)
    setError('')
    try {
      const result = await cancelTickets(token, detail.id, selectedTickets, idempotencyKey)
      setRefund(result)
      load()
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  async function resolveRefund(action: 'reconcile' | 'retry') {
    if (!refund || busy) return
    setBusy(true)
    setError('')
    try {
      setRefund(action === 'reconcile'
        ? await reconcileRefund(token, refund.id)
        : await retryRefund(token, refund.id, idempotencyKey))
      load()
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="content" aria-labelledby="bookings-heading">
      {selectedId && <button className="back" type="button" onClick={() => { setLoading(true); setError(''); setSelectedId(null); setDetail(null) }}>← All bookings</button>}
      <p className="eyebrow">Customer</p><h1 id="bookings-heading">{selectedId ? 'Booking details' : 'My bookings'}</h1>
      {loading && <Loading>Loading bookings…</Loading>}
      {error && <ErrorPanel message={error} retry={load} />}
      {!selectedId && !loading && !error && items.length === 0 && <p className="empty">You have no bookings yet.</p>}
      {!selectedId && !loading && items.length > 0 && <div className="stack-list">{items.map((booking) => (
        <article key={booking.id} className="list-card">
          <div><StatusBadge>{booking.state.replaceAll('_', ' ')}</StatusBadge><h2>{booking.eventName}</h2><p>Reference {booking.reference}</p></div>
          <div><strong>{formatMinor(booking.totalMinor, booking.currency)}</strong><button type="button" onClick={() => open(booking.id)}>Manage</button></div>
        </article>
      ))}</div>}
      {detail && !loading && (
        <div className="action-card wide">
          <p><StatusBadge>{detail.state.replaceAll('_', ' ')}</StatusBadge></p>
          <p>Event starts {formatDate(detail.startsAt)}.</p>
          <form onSubmit={(event) => void cancel(event)}>
            <fieldset disabled={busy}>
              <legend>Select confirmed tickets to cancel</legend>
              <div className="ticket-list">{detail.tickets.map((ticket) => {
                const eligible = ticket.state === 'CONFIRMED'
                return <label key={ticket.id} className="check-row"><input type="checkbox" disabled={!eligible} checked={selectedTickets.includes(ticket.id)} onChange={() => setSelectedTickets((current) => current.includes(ticket.id) ? current.filter((id) => id !== ticket.id) : [...current, ticket.id])} /> <span>{ticket.section}, row {ticket.row}, seat {ticket.number} · {formatMinor(ticket.chargedTotalMinor, ticket.currency)} · {ticket.state.replaceAll('_', ' ')}</span></label>
              })}</div>
              <button className="danger" type="submit" disabled={selectedTickets.length === 0 || busy}>{busy ? 'Submitting once…' : 'Cancel selected tickets'}</button>
            </fieldset>
          </form>
          {refund && <div className="result-box" role="status"><strong>Refund {refund.state.replaceAll('_', ' ')}</strong><p>{formatMinor(refund.amountMinor, refund.currency)} at {refund.percentageBps / 100}%.</p>{refund.state === 'UNKNOWN' && <button type="button" disabled={busy} onClick={() => void resolveRefund('reconcile')}>Check refund status</button>}{refund.state === 'FAILED' && <button type="button" disabled={busy} onClick={() => void resolveRefund('retry')}>Retry refund</button>}</div>}
        </div>
      )}
    </section>
  )
}

function Waitlist({ token, onLogout, onBooked }: Props & { onBooked: () => void }) {
  const [entries, setEntries] = useState<WaitlistEntry[]>([])
  const [claim, setClaim] = useState<WaitlistClaim | null>(null)
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState('')
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    listWaitlist(token, controller.signal).then(setEntries).catch((cause: unknown) => {
      if (isAbort(cause) || unauthorized(cause, onLogout)) return
      setError(errorMessage(cause))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [onLogout, retryKey, token])

  async function act(entry: WaitlistEntry, action: 'claim' | 'cancel') {
    if (busyId) return
    setBusyId(entry.id)
    setError('')
    try {
      if (action === 'claim') setClaim(await claimWaitlist(token, entry.id))
      else await cancelWaitlist(token, entry.id)
      setRetryKey((value) => value + 1)
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusyId('')
    }
  }

  return (
    <section className="content" aria-labelledby="waitlist-heading">
      <p className="eyebrow">Customer</p><h1 id="waitlist-heading">My waitlist</h1>
      {loading && <Loading>Loading waitlist…</Loading>}
      {error && <ErrorPanel message={error} retry={() => { setLoading(true); setError(''); setRetryKey((value) => value + 1) }} />}
      {claim && <CheckoutPanel token={token} hold={claim} onLogout={onLogout} onBooked={onBooked} />}
      {!loading && !error && entries.length === 0 && <p className="empty">You have no waitlist entries.</p>}
      {!loading && entries.length > 0 && <div className="stack-list">{entries.map((entry) => (
        <article className="list-card" key={entry.id}>
          <div><StatusBadge>{entry.state}</StatusBadge><h2>{entry.quantity} {entry.quantity === 1 ? 'seat' : 'seats'}</h2><p>Joined {formatDate(entry.createdAt)}</p>{entry.offerExpiresAt && <p>Offer expires {formatDate(entry.offerExpiresAt)}</p>}</div>
          <div className="button-stack">{entry.state === 'OFFERED' && <button className="primary" type="button" disabled={Boolean(busyId)} onClick={() => void act(entry, 'claim')}>{busyId === entry.id ? 'Claiming…' : 'Claim offer'}</button>}{['WAITING', 'OFFERED'].includes(entry.state) && <button type="button" disabled={Boolean(busyId)} onClick={() => void act(entry, 'cancel')}>Leave waitlist</button>}</div>
        </article>
      ))}</div>}
    </section>
  )
}
