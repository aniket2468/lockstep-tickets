export type Money = { amountMinor: number; currency: string }
export type Role = 'CUSTOMER' | 'ADMIN'

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

function cookie(name: string) {
  const prefix = `${encodeURIComponent(name)}=`
  const value = document.cookie.split('; ').find((item) => item.startsWith(prefix))
  return value ? decodeURIComponent(value.slice(prefix.length)) : ''
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  if (init.method && !['GET', 'HEAD', 'OPTIONS'].includes(init.method.toUpperCase())) {
    const csrfToken = cookie('lockstep_csrf')
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken)
  }

  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
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

export const currentAccount = () =>
  request<LoginResult['account']>('/api/auth/me')

export const logout = () =>
  request<void>('/api/auth/logout', { method: 'POST' })

export function listEvents(query: string, signal?: AbortSignal) {
  const search = new URLSearchParams({ limit: '20', offset: '0' })
  if (query) search.set('query', query)
  return request<EventPage>(`/api/events?${search.toString()}`, { signal })
}

export const getEvent = (eventId: string, signal?: AbortSignal) =>
  request<EventDetail>(`/api/events/${encodeURIComponent(eventId)}`, { signal })

export const createHold = (eventId: string, seatIds: string[], promoCode: string) =>
  request<Hold>(`/api/events/${encodeURIComponent(eventId)}/holds`, json({
    seatIds,
    promoCode: promoCode.trim() || null,
  }))

export const listActiveHolds = (signal?: AbortSignal) =>
  request<Hold[]>('/api/holds/active', { signal })

export const checkout = (holdId: string, key: string) =>
  request<Booking>(`/api/holds/${encodeURIComponent(holdId)}/checkout`, withKey({}, key))

export const reconcileBooking = (bookingId: string) =>
  request<Booking>(`/api/bookings/${encodeURIComponent(bookingId)}/reconcile`, { method: 'POST' })

export const listBookings = (signal?: AbortSignal) =>
  request<Booking[]>('/api/bookings', { signal })

export const getBooking = (bookingId: string, signal?: AbortSignal) =>
  request<BookingDetail>(`/api/bookings/${encodeURIComponent(bookingId)}`, { signal })

export const cancelTickets = (bookingId: string, ticketIds: string[], key: string) =>
  request<Refund>(`/api/bookings/${encodeURIComponent(bookingId)}/cancellations`, withKey({ ticketIds }, key))

export const reconcileRefund = (refundId: string) =>
  request<Refund>(`/api/refunds/${encodeURIComponent(refundId)}/reconcile`, { method: 'POST' })

export const retryRefund = (refundId: string, key: string) =>
  request<Refund>(`/api/refunds/${encodeURIComponent(refundId)}/retry`, withKey({}, key))

export const listWaitlist = (signal?: AbortSignal) =>
  request<WaitlistEntry[]>('/api/waitlist', { signal })

export const joinWaitlist = (eventId: string, quantity: number) =>
  request<WaitlistEntry>(`/api/events/${encodeURIComponent(eventId)}/waitlist`, json({ preferredSectionId: null, quantity }))

export const claimWaitlist = (entryId: string) =>
  request<WaitlistClaim>(`/api/waitlist/${encodeURIComponent(entryId)}/claim`, { method: 'POST' })

export const cancelWaitlist = (entryId: string) =>
  request<WaitlistEntry>(`/api/waitlist/${encodeURIComponent(entryId)}`, { method: 'DELETE' })

export const mutateSeat = (eventId: string, seatId: string, action: 'block' | 'release', reason: string) =>
  request<{ id: string; inventoryState: string }>(`/api/admin/events/${encodeURIComponent(eventId)}/seats/${encodeURIComponent(seatId)}/${action}`, json({ reason }))

export const rescheduleEvent = (eventId: string, startsAt: string, reason: string) =>
  request<{ id: string; state: string; previousStartsAt: string; startsAt: string; refundWindowEndsAt: string }>(`/api/admin/events/${encodeURIComponent(eventId)}/reschedule`, json({ startsAt, reason }))

export const cancelEvent = (eventId: string, reason: string) =>
  request<{ id: string; state: string; refunds: Refund[] }>(`/api/admin/events/${encodeURIComponent(eventId)}/cancel`, json({ reason }))

export const promoteWaitlist = (eventId: string, reason: string) =>
  request<{ entryId: string; offerHoldId: string; state: string; expiresAt: string; seatIds: string[] }>(`/api/admin/events/${encodeURIComponent(eventId)}/waitlist/promotions`, json({ reason }))

export const exceptionalRefund = (bookingId: string, ticketIds: string[], key: string, reason: string) =>
  request<{ id: string; state: string; replayed: boolean }>(`/api/admin/bookings/${encodeURIComponent(bookingId)}/refunds`, withKey({ ticketIds, reason }, key))

export const getSalesReport = (eventId: string, signal?: AbortSignal) =>
  request<SalesReport>(`/api/admin/reports/events/${encodeURIComponent(eventId)}/sales`, { signal })

export const getOccupancyReport = (eventId: string, signal?: AbortSignal) =>
  request<OccupancyReport>(`/api/admin/reports/events/${encodeURIComponent(eventId)}/occupancy`, { signal })

export const getAudit = (signal?: AbortSignal) =>
  request<AuditRow[]>('/api/admin/audit?limit=50&offset=0', { signal })
