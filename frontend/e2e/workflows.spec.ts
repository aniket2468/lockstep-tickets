import { expect, Page, test } from '@playwright/test'

const event = {
  id: 'event-1',
  name: 'Browser Test Concert',
  state: 'ON_SALE',
  startsAt: '2030-06-10T23:30:00Z',
  timeZone: 'America/Indiana/Indianapolis',
  venue: { name: 'Lockstep Hall', address: '1 Test Way' },
  fromPrice: { amountMinor: 5000, currency: 'USD' },
}

async function mockApi(page: Page, role: 'CUSTOMER' | 'ADMIN') {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let body: unknown
    let status = 200
    if (path === '/api/auth/login') {
      body = { accessToken: 'browser-token', expiresAt: '2030-01-01T00:00:00Z', account: { id: 'account-1', email: `${role.toLowerCase()}@example.com`, role } }
    } else if (path === '/api/events') {
      body = { items: [event], limit: 20, offset: 0, hasMore: false }
    } else if (path === '/api/events/event-1') {
      body = { ...event, serviceFee: { amountMinor: 250, currency: 'USD' }, seats: [{ id: 'seat-1', sectionName: 'Orchestra', rowLabel: 'A', seatNumber: '1', accessible: false, availability: 'AVAILABLE', priceTier: 'Standard', price: event.fromPrice }] }
    } else if (path === '/api/events/event-1/holds') {
      status = 201
      body = { id: 'hold-1', eventId: 'event-1', state: 'ACTIVE', createdAt: '2030-01-01T00:00:00Z', expiresAt: '2030-01-01T00:10:00Z', seatIds: ['seat-1'], promoCode: null, currency: 'USD', subtotalMinor: 5000, discountMinor: 0, serviceFeeMinor: 250, totalMinor: 5250 }
    } else if (path === '/api/holds/hold-1/checkout') {
      status = 201
      body = { id: 'booking-1', reference: 'LS-BROWSER', holdId: 'hold-1', state: 'CONFIRMED', currency: 'USD', subtotalMinor: 5000, discountMinor: 0, serviceFeeMinor: 250, totalMinor: 5250 }
    } else if (path.endsWith('/sales')) {
      body = { eventId: 'event-1', currency: 'USD', bookingCount: 1, ticketCount: 1, activeTicketCount: 1, refundedTicketCount: 0, grossSalesMinor: 5250, refundedMinor: 0, pendingRefundMinor: 0, netSalesMinor: 5250 }
    } else if (path.endsWith('/occupancy')) {
      body = { eventId: 'event-1', eventState: 'ON_SALE', salesOpen: true, totalSeats: 1, blockedSeats: 0, soldSeats: 1, paymentPendingSeats: 0, waitlistOfferedSeats: 0, heldSeats: 0, unallocatedSeats: 0, reconciledSeats: 1 }
    } else {
      status = 404
      body = { error: { message: `Unmocked path: ${path}` } }
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function signIn(page: Page, role: 'CUSTOMER' | 'ADMIN') {
  await page.goto('/')
  await page.getByLabel('Email').fill(`${role.toLowerCase()}@example.com`)
  await page.getByLabel('Password').fill('browser-test-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
}

test('customer completes an accessible seat-to-confirmation workflow', async ({ page }) => {
  await mockApi(page, 'CUSTOMER')
  await signIn(page, 'CUSTOMER')
  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
  await page.getByRole('button', { name: 'View seats' }).click()
  const seat = page.getByRole('button', { name: /row A, seat 1, available/i })
  await seat.click()
  await expect(page.getByRole('button', { name: /row A, seat 1, selected/i })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Hold selected seats' }).click()
  await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible()
  await expect(page.getByText('$52.50')).toBeVisible()
  await page.getByRole('button', { name: 'Complete checkout' }).click()
  await expect(page.getByRole('heading', { name: 'Booking LS-BROWSER' })).toBeVisible()
  await expect(page.getByText('CONFIRMED')).toBeVisible()
})

test('admin sees reconciled reports at a narrow responsive viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockApi(page, 'ADMIN')
  await signIn(page, 'ADMIN')
  await expect(page.getByRole('heading', { name: 'Events & reports' })).toBeVisible()
  await page.getByRole('button', { name: 'Manage event' }).click()
  await expect(page.getByRole('heading', { name: 'Reconciled reports' })).toBeVisible()
  await expect(page.getByLabel('1 of 1 seats reconciled')).toBeVisible()
  await expect(page.getByText('$52.50')).toHaveCount(2)
})
