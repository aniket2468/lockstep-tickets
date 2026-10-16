export type Money = {
  amountMinor: number
  currency: string
}

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

export type EventDetail = EventSummary & {
  serviceFee: Money
  seats: EventSeat[]
}

export type EventPage = {
  items: EventSummary[]
  limit: number
  offset: number
  hasMore: boolean
}

export type LoginResult = {
  accessToken: string
  expiresAt: string
  account: { id: string; email: string; role: string }
}

type ApiErrorBody = {
  error?: { code?: string; message?: string; requestId?: string }
}

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
  if (response.ok) return (await response.json()) as T

  let body: ApiErrorBody = {}
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Keep proxy HTML and malformed server responses out of the interface.
  }
  throw new ApiError(
    body.error?.message ?? 'The request could not be completed.',
    response.status,
    body.error?.code,
    body.error?.requestId,
  )
}

export function login(email: string, password: string): Promise<LoginResult> {
  return request<LoginResult>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
}

export function listEvents(token: string, query: string, signal?: AbortSignal): Promise<EventPage> {
  const search = new URLSearchParams({ limit: '20', offset: '0' })
  if (query) search.set('query', query)
  return request<EventPage>(`/api/events?${search.toString()}`, { signal }, token)
}

export function getEvent(token: string, eventId: string, signal?: AbortSignal): Promise<EventDetail> {
  return request<EventDetail>(`/api/events/${encodeURIComponent(eventId)}`, { signal }, token)
}
