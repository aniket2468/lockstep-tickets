import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { App } from './App'

const event = {
  id: 'b23fec6f-24b8-44bb-b45f-65fc3a74ca02',
  name: 'Night at the Symphony',
  state: 'ON_SALE',
  startsAt: '2030-06-10T23:30:00Z',
  timeZone: 'America/Indiana/Indianapolis',
  venue: { name: 'Lockstep Hall', address: '1 Test Way' },
  fromPrice: { amountMinor: 5000, currency: 'USD' },
} as const

const availableSeat = { id: 'seat-1', sectionName: 'Orchestra', rowLabel: 'A', seatNumber: '1', accessible: false, availability: 'AVAILABLE', priceTier: 'Standard', price: event.fromPrice }
const unavailableSeat = { id: 'seat-2', sectionName: 'Orchestra', rowLabel: 'A', seatNumber: '2', accessible: true, availability: 'UNAVAILABLE', priceTier: 'Standard', price: event.fromPrice }
const detail = { ...event, serviceFee: { amountMinor: 250, currency: 'USD' }, seats: [availableSeat, unavailableSeat] }

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }))
}

function loginResult(role: 'CUSTOMER' | 'ADMIN' = 'CUSTOMER') {
  return {
    expiresAt: '2030-01-01T00:00:00Z',
    account: { id: 'account-1', email: `${role.toLowerCase()}@example.com`, role },
  }
}

async function signIn(role: 'CUSTOMER' | 'ADMIN' = 'CUSTOMER') {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: `${role.toLowerCase()}@example.com` } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse battery staple' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await screen.findByRole('heading', { name: role === 'CUSTOMER' ? 'Choose an event.' : 'Events & reports' })
}

function requestUrl(call: unknown[]) {
  const input = call[0]
  return typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url
}

function requestBody(call: unknown[]) {
  const body = (call[1] as RequestInit | undefined)?.body
  if (typeof body !== 'string') throw new Error('Expected a JSON request body.')
  return JSON.parse(body) as Record<string, unknown>
}

beforeEach(() => {
  document.cookie = 'lockstep_csrf=; Max-Age=0; path=/'
  vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
})

afterEach(() => {
  cleanup()
  document.cookie = 'lockstep_csrf=; Max-Age=0; path=/'
  vi.restoreAllMocks()
})

describe('secure session boundary', () => {
  it('starts with an accessible sign-in form and keeps credentials out of browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Find your next seat.' })).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
    expect(screen.getByText(/protected, JavaScript-inaccessible cookie/i)).toBeInTheDocument()
    await signIn()
    expect(setItem).not.toHaveBeenCalled()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    const authenticatedRequest = vi.mocked(fetch).mock.calls[1]
    expect(new Headers(authenticatedRequest[1]?.headers).get('Authorization')).toBeNull()
    expect(authenticatedRequest[1]?.credentials).toBe('same-origin')
  })

  it.each([
    { role: 'CUSTOMER' as const, heading: 'Choose an event.' },
    { role: 'ADMIN' as const, heading: 'Events & reports' },
  ])('restores an existing $role secure session after a page reload', async ({ role, heading }) => {
    document.cookie = 'lockstep_csrf=csrf-test; path=/'
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult(role).account))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))

    render(<App />)

    expect(screen.getByRole('status')).toHaveTextContent('Restoring your secure session')
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
    expect(requestUrl(vi.mocked(fetch).mock.calls[0])).toBe('/api/auth/me')
  })

  it('redacts the password after a stable login error and rejects an unknown server role', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response({ error: { message: 'The credentials are invalid.', requestId: 'request-123' } }, 401))
      .mockImplementationOnce(() => response({ ...loginResult(), account: { ...loginResult().account, role: 'SUPPORT' } }))
    render(<App />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'customer@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The credentials are invalid. Reference: request-123.')
    expect(screen.getByLabelText('Password')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'another password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This account role is not supported.')
  })

  it('returns to sign-in when any authenticated read expires', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ error: { message: 'Authentication is required.' } }, 401))
    render(<App />)
    await signIn()
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Find your next seat.' })).toBeInTheDocument())
  })

  it('sends the CSRF token when signing out and clears the UI session', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response(undefined, 204))
    render(<App />)
    await signIn()
    document.cookie = 'lockstep_csrf=csrf-test; path=/'

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    await screen.findByRole('heading', { name: 'Find your next seat.' })
    const logoutCall = vi.mocked(fetch).mock.calls[2]
    expect(requestUrl(logoutCall)).toBe('/api/auth/logout')
    expect(logoutCall[1]?.method).toBe('POST')
    expect(new Headers(logoutCall[1]?.headers).get('X-CSRF-Token')).toBe('csrf-test')
  })
})

describe('customer workflows', () => {
  it('encodes literal search input and renders explicit empty state', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
    render(<App />)
    await signIn()
    await screen.findByRole('heading', { name: event.name })
    fireEvent.change(screen.getByLabelText('Search events'), { target: { value: "100%_' OR 1=1" } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('No events match your search.')).toBeInTheDocument()
    expect(requestUrl(vi.mocked(fetch).mock.calls[2])).toContain('query=100%25_%27+OR+1%3D1')
  })

  it('aborts a superseded search so a stale response cannot replace newer results', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => new Promise<Response>(() => undefined))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
    render(<App />)
    await signIn()
    await screen.findByRole('heading', { name: event.name })
    fireEvent.change(screen.getByLabelText('Search events'), { target: { value: 'older' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3))
    const staleSignal = vi.mocked(fetch).mock.calls[2][1]?.signal
    fireEvent.change(screen.getByLabelText('Search events'), { target: { value: 'newer' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText('No events match your search.')).toBeInTheDocument()
    expect(staleSignal?.aborted).toBe(true)
    expect(requestUrl(vi.mocked(fetch).mock.calls[3])).toContain('query=newer')
  })

  it('selects only available seats, creates a server quote, and checks out with one idempotency key', async () => {
    const hold = { id: 'hold-1', eventId: event.id, state: 'ACTIVE', createdAt: '2030-01-01T00:00:00Z', expiresAt: '2030-01-01T00:10:00Z', seatIds: ['seat-1'], promoCode: null, currency: 'USD', subtotalMinor: 5000, discountMinor: 500, serviceFeeMinor: 250, totalMinor: 4750, lines: [] }
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response(detail))
      .mockImplementationOnce(() => response(hold, 201))
      .mockImplementationOnce(() => response({ id: 'booking-1', reference: 'LS-123', holdId: 'hold-1', state: 'CONFIRMED', currency: 'USD', subtotalMinor: 5000, discountMinor: 500, serviceFeeMinor: 250, totalMinor: 4750 }, 201))
    render(<App />)
    await signIn()
    fireEvent.click(await screen.findByRole('button', { name: 'View seats' }))
    expect(await screen.findByRole('button', { name: /seat 2, unavailable/i })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /seat 1, available/i }))
    expect(screen.getByRole('button', { name: /seat 1, selected/i })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(screen.getByLabelText('Promo code (optional)'), { target: { value: ' SAVE10 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Hold selected seats' }))
    expect(await screen.findByRole('heading', { name: 'Checkout' })).toBeInTheDocument()
    expect(screen.getByText('$47.50')).toBeInTheDocument()
    expect(screen.queryByLabelText('Mock provider result')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Status lookup result')).not.toBeInTheDocument()
    expect(requestBody(vi.mocked(fetch).mock.calls[3])).toEqual({ seatIds: ['seat-1'], promoCode: 'SAVE10' })
    fireEvent.click(screen.getByRole('button', { name: 'Complete checkout' }))
    expect(await screen.findByRole('heading', { name: 'Booking LS-123' })).toBeInTheDocument()
    const checkoutCall = vi.mocked(fetch).mock.calls[4]
    expect(new Headers(checkoutCall[1]?.headers).get('Idempotency-Key')).toBe('ui-11111111-1111-4111-8111-111111111111')
    expect(requestBody(checkoutCall)).toEqual({})
  })

  it('offers sold-out waitlisting without pretending to reserve or purchase seats', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response({ ...detail, seats: [unavailableSeat] }))
      .mockImplementationOnce(() => response({ id: 'entry-1', eventId: event.id, preferredSectionId: null, quantity: 2, state: 'WAITING', offerHoldId: null, claimedHoldId: null, offerExpiresAt: null, createdAt: '2030-01-01T00:00:00Z' }, 201))
    render(<App />)
    await signIn()
    fireEvent.click(await screen.findByRole('button', { name: 'View seats' }))
    fireEvent.change(await screen.findByLabelText('Number of seats'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Join waitlist' }))
    expect(await screen.findByText(/offers must be claimed within 15 minutes/i)).toBeInTheDocument()
    expect(requestBody(vi.mocked(fetch).mock.calls[3])).toEqual({ preferredSectionId: null, quantity: 2 })
  })

  it('loads owned booking details and submits only explicitly selected tickets', async () => {
    const summary = { id: 'booking-1', reference: 'LS-123', state: 'CONFIRMED', currency: 'USD', totalMinor: 5250, eventName: event.name, startsAt: event.startsAt }
    const ticket = { id: 'ticket-1', eventSeatId: 'seat-1', state: 'CONFIRMED', chargedTotalMinor: 5250, serviceFeeMinor: 250, currency: 'USD', section: 'Orchestra', row: 'A', number: '1' }
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response([summary]))
      .mockImplementationOnce(() => response({ id: 'booking-1', eventId: event.id, state: 'CONFIRMED', currency: 'USD', startsAt: event.startsAt, tickets: [ticket] }))
      .mockImplementationOnce(() => response({ id: 'refund-1', bookingId: 'booking-1', state: 'SUCCEEDED', percentageBps: 10000, amountMinor: 5000, currency: 'USD' }, 201))
      .mockImplementationOnce(() => response({ id: 'booking-1', eventId: event.id, state: 'REFUNDED', currency: 'USD', startsAt: event.startsAt, tickets: [{ ...ticket, state: 'REFUNDED' }] }))
    render(<App />)
    await signIn()
    fireEvent.click(screen.getByRole('button', { name: 'My bookings' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Manage' }))
    const checkbox = await screen.findByRole('checkbox', { name: /orchestra, row a, seat 1/i })
    fireEvent.click(checkbox)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel selected tickets' }))
    expect(await screen.findByText('Refund SUCCEEDED')).toBeInTheDocument()
    const call = vi.mocked(fetch).mock.calls[4]
    expect(requestBody(call)).toEqual({ ticketIds: ['ticket-1'] })
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toMatch(/^ui-/)
  })

  it('claims a waitlist offer into a fresh hold before exposing checkout', async () => {
    const entry = { id: 'entry-1', eventId: event.id, preferredSectionId: null, quantity: 1, state: 'OFFERED', offerHoldId: 'offer-1', claimedHoldId: null, offerExpiresAt: '2030-01-01T00:15:00Z', createdAt: '2030-01-01T00:00:00Z' }
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult()))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response([entry]))
      .mockImplementationOnce(() => response({ entryId: 'entry-1', holdId: 'hold-1', eventId: event.id, state: 'CLAIMED', createdAt: '2030-01-01T00:01:00Z', expiresAt: '2030-01-01T00:11:00Z', seatIds: ['seat-1'], currency: 'USD', subtotalMinor: 5000, discountMinor: 0, serviceFeeMinor: 250, totalMinor: 5250 }, 201))
      .mockImplementationOnce(() => response([{ ...entry, state: 'CLAIMED', claimedHoldId: 'hold-1' }]))
    render(<App />)
    await signIn()
    fireEvent.click(screen.getByRole('button', { name: 'My waitlist' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Claim offer' }))
    expect(await screen.findByRole('heading', { name: 'Checkout' })).toBeInTheDocument()
    expect(requestUrl(vi.mocked(fetch).mock.calls[3])).toContain('/api/waitlist/entry-1/claim')
  })
})

describe('admin workflows', () => {
  it('routes administrators to reconciled reports and submits an audited seat override', async () => {
    const sales = { eventId: event.id, currency: 'USD', bookingCount: 1, ticketCount: 2, activeTicketCount: 1, refundedTicketCount: 1, grossSalesMinor: 10000, refundedMinor: 5000, pendingRefundMinor: 0, netSalesMinor: 5000 }
    const occupancy = { eventId: event.id, eventState: 'ON_SALE', salesOpen: true, totalSeats: 2, blockedSeats: 0, soldSeats: 1, paymentPendingSeats: 0, waitlistOfferedSeats: 0, heldSeats: 0, unallocatedSeats: 1, reconciledSeats: 2 }
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult('ADMIN')))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response(detail))
      .mockImplementationOnce(() => response(sales))
      .mockImplementationOnce(() => response(occupancy))
      .mockImplementationOnce(() => response({ id: 'seat-1', inventoryState: 'BLOCKED' }))
      .mockImplementationOnce(() => response(detail))
      .mockImplementationOnce(() => response(sales))
      .mockImplementationOnce(() => response({ ...occupancy, blockedSeats: 1, unallocatedSeats: 0 }))
    render(<App />)
    await signIn('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Manage event' }))
    expect(await screen.findByText('$100.00')).toBeInTheDocument()
    expect(screen.getByLabelText('2 of 2 seats reconciled')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('New start instant'), { target: { value: '2030-06-10 19:30' } })
    fireEvent.change(screen.getByLabelText('Audit reason', { selector: '#reschedule-reason' }), { target: { value: 'Tour delay' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule event' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/valid ISO 8601/i)
    expect(fetch).toHaveBeenCalledTimes(5)
    fireEvent.change(screen.getByLabelText('Seat'), { target: { value: 'seat-1' } })
    fireEvent.change(screen.getByLabelText('Audit reason', { selector: '#seat-reason' }), { target: { value: 'Sightline maintenance' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply inventory override' }))
    expect(await screen.findByText(/completed and audited/i)).toBeInTheDocument()
    expect(requestBody(vi.mocked(fetch).mock.calls[5])).toEqual({ reason: 'Sightline maintenance' })
    expect(requestUrl(vi.mocked(fetch).mock.calls[5])).toContain('/seats/seat-1/block')
  })

  it('renders the append-only audit trail and server-provided reasons', async () => {
    const row = { id: 'audit-1', actorAccountId: 'admin-1', action: 'SEAT_BLOCKED', entityType: 'EVENT_SEAT', entityId: 'seat-1', reason: 'Safety inspection', createdAt: '2030-01-01T00:00:00Z' }
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response(loginResult('ADMIN')))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response([row]))
    render(<App />)
    await signIn('ADMIN')
    fireEvent.click(screen.getByRole('button', { name: 'Audit trail' }))
    expect(await screen.findByText('Safety inspection')).toBeInTheDocument()
    expect(screen.getByText('SEAT BLOCKED')).toBeInTheDocument()
    expect(requestUrl(vi.mocked(fetch).mock.calls[2])).toBe('/api/admin/audit?limit=50&offset=0')
  })
})
