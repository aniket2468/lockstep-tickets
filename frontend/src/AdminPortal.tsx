import { FormEvent, useEffect, useState } from 'react'

import {
  ApiError,
  AuditRow,
  cancelEvent,
  EventDetail,
  EventSummary,
  exceptionalRefund,
  getAudit,
  getEvent,
  getOccupancyReport,
  getSalesReport,
  listEvents,
  mutateSeat,
  OccupancyReport,
  promoteWaitlist,
  rescheduleEvent,
  SalesReport,
} from './api'
import { ErrorPanel, Loading, StatusBadge, SuccessPanel } from './ui'
import { errorMessage, formText, formatDate, formatMinor, isAbort, newIdempotencyKey } from './utils'

type Props = { onLogout: () => void }
type Tab = 'operations' | 'refunds' | 'audit'

function unauthorized(error: unknown, onLogout: () => void) {
  if (error instanceof ApiError && error.status === 401) {
    onLogout()
    return true
  }
  return false
}

export function AdminPortal({ onLogout }: Props) {
  const [tab, setTab] = useState<Tab>('operations')
  return (
    <>
      <nav className="tabs" aria-label="Administration tools">
        {(['operations', 'refunds', 'audit'] as const).map((item) => (
          <button key={item} className={tab === item ? 'active' : ''} type="button" aria-current={tab === item ? 'page' : undefined} onClick={() => setTab(item)}>
            {item === 'operations' ? 'Events & reports' : item === 'refunds' ? 'Exceptional refunds' : 'Audit trail'}
          </button>
        ))}
      </nav>
      {tab === 'operations' && <EventOperations onLogout={onLogout} />}
      {tab === 'refunds' && <ExceptionalRefund onLogout={onLogout} />}
      {tab === 'audit' && <Audit onLogout={onLogout} />}
    </>
  )
}

function EventOperations({ onLogout }: Props) {
  const [events, setEvents] = useState<EventSummary[]>([])
  const [eventId, setEventId] = useState('')
  const [detail, setDetail] = useState<EventDetail | null>(null)
  const [seatId, setSeatId] = useState('')
  const [sales, setSales] = useState<SalesReport | null>(null)
  const [occupancy, setOccupancy] = useState<OccupancyReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const reads = eventId
      ? Promise.all([
          getEvent(eventId, controller.signal),
          getSalesReport(eventId, controller.signal),
          getOccupancyReport(eventId, controller.signal),
        ])
      : listEvents('', controller.signal)
    reads.then((result) => {
      if (Array.isArray(result)) {
        setDetail(result[0])
        setSales(result[1])
        setOccupancy(result[2])
      } else {
        setEvents(result.items)
      }
    }).catch((cause: unknown) => {
      if (isAbort(cause) || unauthorized(cause, onLogout)) return
      setError(errorMessage(cause))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [eventId, onLogout, retryKey])

  async function operation(label: string, action: () => Promise<unknown>) {
    if (busy) return
    setBusy(true)
    setError('')
    setSuccess('')
    try {
      await action()
      setSuccess(label)
      setLoading(true)
      setRetryKey((value) => value + 1)
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  function reason(form: HTMLFormElement) {
    return formText(new FormData(form), 'reason').trim()
  }

  function submitReschedule(form: HTMLFormElement) {
    const value = formText(new FormData(form), 'startsAt').trim()
    const parsed = new Date(value)
    const explicitInstant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?(?:Z|[+-]\d{2}:\d{2})$/u
    if (!explicitInstant.test(value) || Number.isNaN(parsed.valueOf())) {
      setError('Enter a valid ISO 8601 date and time with an offset or Z suffix.')
      return
    }
    void operation('Event rescheduled and audited.', () => rescheduleEvent(detail?.id ?? '', parsed.toISOString(), reason(form)))
  }

  return (
    <section className="content" aria-labelledby="admin-events-heading">
      {eventId && <button className="back" type="button" onClick={() => { setLoading(true); setError(''); setEventId(''); setDetail(null); setSales(null); setOccupancy(null); setSeatId('') }}>← All events</button>}
      <p className="eyebrow">Administration</p><h1 id="admin-events-heading">{eventId ? 'Event control room' : 'Events & reports'}</h1>
      {loading && <Loading>Loading administration data…</Loading>}
      {error && <ErrorPanel message={error} retry={() => { setLoading(true); setError(''); setRetryKey((value) => value + 1) }} />}
      {success && <SuccessPanel>{success}</SuccessPanel>}
      {!eventId && !loading && events.length === 0 && <p className="empty">No manageable events are visible.</p>}
      {!eventId && !loading && <div className="event-grid">{events.map((event) => (
        <article className="event-card" key={event.id}><StatusBadge>{event.state.replaceAll('_', ' ')}</StatusBadge><h2>{event.name}</h2><p>{event.venue.name}</p><time>{formatDate(event.startsAt, event.timeZone)}</time><div className="card-footer"><span>{event.fromPrice ? formatMinor(event.fromPrice.amountMinor, event.fromPrice.currency) : 'No price'}</span><button type="button" onClick={() => { setLoading(true); setError(''); setEventId(event.id) }}>Manage event</button></div></article>
      ))}</div>}
      {detail && !loading && (
        <>
          <div className="section-heading"><div><h2>{detail.name}</h2><p>{formatDate(detail.startsAt, detail.timeZone)} · {detail.venue.name}</p></div><StatusBadge>{detail.state.replaceAll('_', ' ')}</StatusBadge></div>
          {sales && occupancy && <Reports sales={sales} occupancy={occupancy} />}
          <div className="admin-grid">
            <form className="action-card" onSubmit={(event) => { event.preventDefault(); const action = formText(new FormData(event.currentTarget), 'seatAction') as 'block' | 'release'; void operation(`Seat ${action} completed and audited.`, () => mutateSeat(detail.id, seatId, action, reason(event.currentTarget))) }}>
              <h2>Seat inventory</h2>
              <label htmlFor="admin-seat">Seat</label>
              <select id="admin-seat" value={seatId} onChange={(event) => setSeatId(event.target.value)} required>
                <option value="">Choose a seat</option>
                {detail.seats.map((seat) => <option key={seat.id} value={seat.id}>{seat.sectionName} · {seat.rowLabel}{seat.seatNumber} · {seat.availability.toLowerCase()}</option>)}
              </select>
              <label htmlFor="seat-action">Operation</label>
              <select id="seat-action" name="seatAction"><option value="block">Block</option><option value="release">Release block</option></select>
              <ReasonInput id="seat-reason" />
              <button type="submit" disabled={busy || !seatId}>{busy ? 'Applying once…' : 'Apply inventory override'}</button>
              <p className="privacy-note">Unavailable inventory is deliberately masked. The server rejects release of non-blocked seats and block of held or sold seats.</p>
            </form>

            <form className="action-card" onSubmit={(event) => { event.preventDefault(); submitReschedule(event.currentTarget) }}>
              <h2>Reschedule</h2>
              <label htmlFor="starts-at">New start instant</label>
              <input id="starts-at" name="startsAt" type="text" required placeholder="2030-06-10T19:30:00-04:00" />
              <p className="privacy-note">Use ISO 8601 with the venue offset or a UTC Z suffix; the submitted instant is never inferred from the browser time zone.</p>
              <ReasonInput id="reschedule-reason" />
              <button type="submit" disabled={busy}>{busy ? 'Applying once…' : 'Reschedule event'}</button>
            </form>

            <form className="action-card" onSubmit={(event) => { event.preventDefault(); void operation('A FIFO waitlist offer was created and audited.', () => promoteWaitlist(detail.id, reason(event.currentTarget))) }}>
              <h2>Promote waitlist</h2>
              <p>The server chooses the earliest currently eligible entry and reserves its seats for 15 minutes.</p>
              <ReasonInput id="promotion-reason" />
              <button type="submit" disabled={busy}>{busy ? 'Promoting once…' : 'Promote next eligible entry'}</button>
            </form>

            <form className="action-card danger-zone" onSubmit={(event) => { event.preventDefault(); void operation('Event cancellation completed and audited.', () => cancelEvent(detail.id, reason(event.currentTarget))) }}>
              <h2>Cancel event</h2>
              <p>This closes sales and attempts full refunds, including allocated fees.</p>
              <ReasonInput id="cancel-reason" />
              <button className="danger" type="submit" disabled={busy}>{busy ? 'Cancelling once…' : 'Cancel event'}</button>
            </form>
          </div>
        </>
      )}
    </section>
  )
}

function ReasonInput({ id }: { id: string }) {
  return <><label htmlFor={id}>Audit reason</label><textarea id={id} name="reason" required maxLength={500} rows={3} /></>
}

function Reports({ sales, occupancy }: { sales: SalesReport; occupancy: OccupancyReport }) {
  const buckets = [
    ['Sold', occupancy.soldSeats],
    ['Payment pending', occupancy.paymentPendingSeats],
    ['Waitlist offered', occupancy.waitlistOfferedSeats],
    ['Held', occupancy.heldSeats],
    ['Blocked', occupancy.blockedSeats],
    ['Unallocated', occupancy.unallocatedSeats],
  ] as const
  return (
    <section className="reports" aria-labelledby="reports-heading">
      <div className="section-heading"><h2 id="reports-heading">Reconciled reports</h2><StatusBadge>{occupancy.salesOpen ? 'SALES OPEN' : 'SALES CLOSED'}</StatusBadge></div>
      <div className="metric-grid">
        <div><span>Net sales</span><strong>{formatMinor(sales.netSalesMinor, sales.currency)}</strong></div>
        <div><span>Gross sales</span><strong>{formatMinor(sales.grossSalesMinor, sales.currency)}</strong></div>
        <div><span>Refunded</span><strong>{formatMinor(sales.refundedMinor, sales.currency)}</strong></div>
        <div><span>Pending refunds</span><strong>{formatMinor(sales.pendingRefundMinor, sales.currency)}</strong></div>
      </div>
      <div className="occupancy" aria-label={`${occupancy.reconciledSeats} of ${occupancy.totalSeats} seats reconciled`}>
        {buckets.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}
      </div>
      <p className="privacy-note">All {occupancy.reconciledSeats} of {occupancy.totalSeats} seats are classified exactly once.</p>
    </section>
  )
}

function ExceptionalRefund({ onLogout }: Props) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [key, setKey] = useState(newIdempotencyKey)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setSuccess('')
    const data = new FormData(event.currentTarget)
    const ticketIds = formText(data, 'ticketIds').split(',').map((value) => value.trim()).filter(Boolean)
    try {
      const result = await exceptionalRefund(formText(data, 'bookingId').trim(), ticketIds, key,
        formText(data, 'reason').trim())
      setSuccess(`Exceptional refund is ${result.state.toLowerCase().replaceAll('_', ' ')}.`)
      setKey(newIdempotencyKey())
      event.currentTarget.reset()
    } catch (cause) {
      if (!unauthorized(cause, onLogout)) setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="content narrow" aria-labelledby="exceptional-heading">
      <p className="eyebrow">Administration</p><h1 id="exceptional-heading">Exceptional refund</h1>
      <p className="summary">Use immutable booking and ticket identifiers from an investigated support case. The server validates ownership and remaining refundable amounts.</p>
      {error && <ErrorPanel message={error} />}{success && <SuccessPanel>{success}</SuccessPanel>}
      <form className="action-card" onSubmit={(event) => void submit(event)}>
        <label htmlFor="booking-id">Booking ID</label><input id="booking-id" name="bookingId" required />
        <label htmlFor="ticket-ids">Ticket IDs, comma separated</label><textarea id="ticket-ids" name="ticketIds" rows={3} required />
        <ReasonInput id="refund-reason" />
        <button className="danger" type="submit" disabled={busy}>{busy ? 'Submitting once…' : 'Issue exceptional refund'}</button>
      </form>
    </section>
  )
}

function Audit({ onLogout }: Props) {
  const [rows, setRows] = useState<AuditRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [retryKey, setRetryKey] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    getAudit(controller.signal).then(setRows).catch((cause: unknown) => {
      if (isAbort(cause) || unauthorized(cause, onLogout)) return
      setError(errorMessage(cause))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [onLogout, retryKey])

  return (
    <section className="content" aria-labelledby="audit-heading">
      <p className="eyebrow">Administration</p><h1 id="audit-heading">Audit trail</h1>
      {loading && <Loading>Loading audit records…</Loading>}
      {error && <ErrorPanel message={error} retry={() => { setLoading(true); setError(''); setRetryKey((value) => value + 1) }} />}
      {!loading && !error && rows.length === 0 && <p className="empty">No administrative overrides have been recorded.</p>}
      {!loading && rows.length > 0 && <div className="audit-list">{rows.map((row) => (
        <article key={row.id}><div><StatusBadge>{row.action.replaceAll('_', ' ')}</StatusBadge><strong>{row.entityType} · {row.entityId}</strong></div><p>{row.reason}</p><time dateTime={row.createdAt}>{formatDate(row.createdAt)}</time></article>
      ))}</div>}
    </section>
  )
}
