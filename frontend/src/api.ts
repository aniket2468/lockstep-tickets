export type Money = { amountMinor: number; currency: string }
export type Role = 'CUSTOMER' | 'ADMIN'
export type PaymentOutcome = 'SUCCESS' | 'DECLINE' | 'TIMEOUT' | 'UNKNOWN'
export type LookupOutcome = 'SUCCESS' | 'DECLINE' | 'UNKNOWN'

export type EventSummary = {
  id: string
  name: string
  state: 'PUBLISHED' | 'ON_SALE' | 'SALES_CLOSED'
  startsAt: string
  timeZone: string
  venue: { name: string; address: string }
  fromPrice: Money | null
}

export type EventSeat = {
  id: string
  sectionName: string
  rowLabel: string
  seatNumber: string
  accessible: boolean
  availability: 'AVAILABLE' | 'UNAVAILABLE'
  priceTier: string
  price: Money
}

export type EventDetail = EventSummary & { serviceFee: Money; seats: EventSeat[] }
export type EventPage = { items: EventSummary[]; limit: number; offset: number; hasMore: boolean }

export type LoginResult = {
  accessToken: string
  expiresAt: string
  account: { id: string; email: string; role: Role }
}

export type QuoteLine = {
  seatId: string
  basePriceMinor: number
  earlyDiscountMinor: number
  groupDiscountMinor: number
  promoDiscountMinor: number
  serviceFeeMinor: number
  finalTotalMinor: number
}

export type Hold = {
  id: string
  eventId: string
  state: string
  createdAt: string
  expiresAt: string
  seatIds: string[]
  promoCode: string | null
  currency: string
  subtotalMinor: number
  discountMinor: number
  serviceFeeMinor: number
  totalMinor: number
  lines?: QuoteLine[]
}

export type Booking = {
  id: string
  reference: string
  holdId?: string
  state: string
  currency: string
  subtotalMinor?: number
  discountMinor?: number
  serviceFeeMinor?: number
  totalMinor: number
  eventName?: string
  startsAt?: string
}

export type Ticket = {
  id: string
  eventSeatId: string
  state: string
  chargedTotalMinor: number
  serviceFeeMinor: number
  currency: string
  section: string
  row: string
  number: string
}

export type BookingDetail = {
  id: string
  eventId: string
  state: string
  currency: string
  startsAt: string
  tickets: Ticket[]
}

export type Refund = {
  id: string
  bookingId: string
  state: string
  percentageBps: number
  amountMinor: number
  currency: string
}

export type WaitlistEntry = {
  id: string
  eventId: string
  preferredSectionId: string | null
  quantity: number
  state: string
  offerHoldId: string | null
  claimedHoldId: string | null
  offerExpiresAt: string | null
  createdAt: string
}

export type WaitlistClaim = {
  entryId: string
  holdId: string
  eventId: string
  state: string
  createdAt: string
  expiresAt: string
  seatIds: string[]
  currency: string
  subtotalMinor: number
  discountMinor: number
  serviceFeeMinor: number
  totalMinor: number
}

export type SalesReport = {
  eventId: string
  currency: string
  bookingCount: number
  ticketCount: number
  activeTicketCount: number
  refundedTicketCount: number
  grossSalesMinor: number
  refundedMinor: number
  pendingRefundMinor: number
  netSalesMinor: number
}

export type OccupancyReport = {
  eventId: string
  eventState: string
  salesOpen: boolean
  totalSeats: number
  blockedSeats: number
  soldSeats: number
  paymentPendingSeats: number
  waitlistOfferedSeats: number
  heldSeats: number
  unallocatedSeats: number
  reconciledSeats: number
}

export type AuditRow = {
  id: string
  actorAccountId: string
  action: string
  entityType: string
  entityId: string
  reason: string
  createdAt: string
}

type ApiErrorBody = { error?: { code?: string; message?: string; requestId?: string } }

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(path, { ...init, headers })
  if (response.ok) {
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Never render proxy HTML or malformed server responses.
  }
  throw new ApiError(
    body.error?.message ?? 'The request could not be completed.',
    response.status,
    body.error?.code,
    body.error?.requestId,
  )
}

const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) })
const withKey = (body: unknown, key: string): RequestInit => ({
  ...json(body),
  headers: { 'Idempotency-Key': key },
})

export const login = (email: string, password: string) =>
  request<LoginResult>('/api/auth/login', json({ email, password }))

export function listEvents(token: string, query: string, signal?: AbortSignal) {
  const search = new URLSearchParams({ limit: '20', offset: '0' })
  if (query) search.set('query', query)
  return request<EventPage>(`/api/events?${search.toString()}`, { signal }, token)
}

export const getEvent = (token: string, eventId: string, signal?: AbortSignal) =>
  request<EventDetail>(`/api/events/${encodeURIComponent(eventId)}`, { signal }, token)

export const createHold = (token: string, eventId: string, seatIds: string[], promoCode: string) =>
  request<Hold>(`/api/events/${encodeURIComponent(eventId)}/holds`, json({
    seatIds,
    promoCode: promoCode.trim() || null,
  }), token)

export const checkout = (token: string, holdId: string, key: string, paymentOutcome: PaymentOutcome, lookupOutcome: LookupOutcome) =>
  request<Booking>(`/api/holds/${encodeURIComponent(holdId)}/checkout`, withKey({ paymentOutcome, lookupOutcome }, key), token)

export const reconcileBooking = (token: string, bookingId: string) =>
  request<Booking>(`/api/bookings/${encodeURIComponent(bookingId)}/reconcile`, { method: 'POST' }, token)

export const listBookings = (token: string, signal?: AbortSignal) =>
  request<Booking[]>('/api/bookings', { signal }, token)

export const getBooking = (token: string, bookingId: string, signal?: AbortSignal) =>
  request<BookingDetail>(`/api/bookings/${encodeURIComponent(bookingId)}`, { signal }, token)

export const cancelTickets = (token: string, bookingId: string, ticketIds: string[], key: string, refundOutcome: PaymentOutcome, lookupOutcome: LookupOutcome) =>
  request<Refund>(`/api/bookings/${encodeURIComponent(bookingId)}/cancellations`, withKey({ ticketIds, refundOutcome, lookupOutcome }, key), token)

export const reconcileRefund = (token: string, refundId: string) =>
  request<Refund>(`/api/refunds/${encodeURIComponent(refundId)}/reconcile`, { method: 'POST' }, token)

export const retryRefund = (token: string, refundId: string, key: string, refundOutcome: PaymentOutcome, lookupOutcome: LookupOutcome) =>
  request<Refund>(`/api/refunds/${encodeURIComponent(refundId)}/retry`, withKey({ refundOutcome, lookupOutcome }, key), token)

export const listWaitlist = (token: string, signal?: AbortSignal) =>
  request<WaitlistEntry[]>('/api/waitlist', { signal }, token)

export const joinWaitlist = (token: string, eventId: string, quantity: number) =>
  request<WaitlistEntry>(`/api/events/${encodeURIComponent(eventId)}/waitlist`, json({ preferredSectionId: null, quantity }), token)

export const claimWaitlist = (token: string, entryId: string) =>
  request<WaitlistClaim>(`/api/waitlist/${encodeURIComponent(entryId)}/claim`, { method: 'POST' }, token)

export const cancelWaitlist = (token: string, entryId: string) =>
  request<WaitlistEntry>(`/api/waitlist/${encodeURIComponent(entryId)}`, { method: 'DELETE' }, token)

export const mutateSeat = (token: string, eventId: string, seatId: string, action: 'block' | 'release', reason: string) =>
  request<{ id: string; inventoryState: string }>(`/api/admin/events/${encodeURIComponent(eventId)}/seats/${encodeURIComponent(seatId)}/${action}`, json({ reason }), token)

export const rescheduleEvent = (token: string, eventId: string, startsAt: string, reason: string) =>
  request<{ id: string; state: string; previousStartsAt: string; startsAt: string; refundWindowEndsAt: string }>(`/api/admin/events/${encodeURIComponent(eventId)}/reschedule`, json({ startsAt, reason }), token)

export const cancelEvent = (token: string, eventId: string, reason: string, refundOutcome: PaymentOutcome, lookupOutcome: LookupOutcome) =>
  request<{ id: string; state: string; refunds: Refund[] }>(`/api/admin/events/${encodeURIComponent(eventId)}/cancel`, json({ reason, refundOutcome, lookupOutcome }), token)

export const promoteWaitlist = (token: string, eventId: string, reason: string) =>
  request<{ entryId: string; offerHoldId: string; state: string; expiresAt: string; seatIds: string[] }>(`/api/admin/events/${encodeURIComponent(eventId)}/waitlist/promotions`, json({ reason }), token)

export const exceptionalRefund = (token: string, bookingId: string, ticketIds: string[], key: string, reason: string, refundOutcome: PaymentOutcome, lookupOutcome: LookupOutcome) =>
  request<{ id: string; state: string; replayed: boolean }>(`/api/admin/bookings/${encodeURIComponent(bookingId)}/refunds`, withKey({ ticketIds, reason, refundOutcome, lookupOutcome }, key), token)

export const getSalesReport = (token: string, eventId: string, signal?: AbortSignal) =>
  request<SalesReport>(`/api/admin/reports/events/${encodeURIComponent(eventId)}/sales`, { signal }, token)

export const getOccupancyReport = (token: string, eventId: string, signal?: AbortSignal) =>
  request<OccupancyReport>(`/api/admin/reports/events/${encodeURIComponent(eventId)}/occupancy`, { signal }, token)

export const getAudit = (token: string, signal?: AbortSignal) =>
  request<AuditRow[]>('/api/admin/audit?limit=50&offset=0', { signal }, token)
