import { expect, test } from '@playwright/test'

const email = process.env.LOCKSTEP_LIVE_E2E_CUSTOMER_EMAIL ?? 'customer@lockstep.test'
const password = process.env.LOCKSTEP_LIVE_E2E_CUSTOMER_PASSWORD
const adminEmail = process.env.LOCKSTEP_LIVE_E2E_ADMIN_EMAIL
  ?? process.env.LOCKSTEP_DEMO_ADMIN_EMAIL
  ?? 'admin@lockstep.test'
const adminPassword = process.env.LOCKSTEP_LIVE_E2E_ADMIN_PASSWORD
  ?? process.env.LOCKSTEP_DEMO_ADMIN_PASSWORD

if (!password) {
  throw new Error('Set LOCKSTEP_LIVE_E2E_CUSTOMER_PASSWORD to run against the live stack.')
}

test('customer books and refunds a real seat through the live stack', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
  await page.getByLabel('Search events').fill('Lockstep Demo')
  await page.getByRole('button', { name: 'Search' }).click()

  const eventCard = page.locator('article.event-card').filter({ hasText: 'Lockstep Demo' })
  await expect(eventCard).toHaveCount(1)
  await eventCard.getByRole('button', { name: 'View seats' }).click()

  const availableSeat = page.getByLabel('Seats').getByRole('button', { name: /, available$/ }).first()
  await expect(availableSeat, 'The live demo event needs at least one available seat.').toBeEnabled()
  const availableSeatLabel = await availableSeat.getAttribute('aria-label')
  expect(availableSeatLabel).toBeTruthy()
  await availableSeat.click()

  await page.getByRole('button', { name: 'Hold selected seats' }).click()
  await expect(page.getByRole('heading', { name: 'Checkout', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Complete your active checkout.' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Checkout', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Complete checkout' }).click()

  const bookingHeading = page.getByRole('heading', { name: /^Booking [A-Z]{2}-/ })
  await expect(bookingHeading).toBeVisible()
  const bookingReference = (await bookingHeading.textContent())?.replace(/^Booking\s+/, '')
  expect(bookingReference).toBeTruthy()
  await expect(page.getByText('CONFIRMED', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'View my bookings' }).click()
  const bookingCard = page.locator('article.list-card').filter({ hasText: `Reference ${bookingReference}` })
  await expect(bookingCard).toHaveCount(1)
  await bookingCard.getByRole('button', { name: 'Manage' }).click()

  const confirmedTicket = page.getByRole('checkbox', { name: /CONFIRMED$/ }).first()
  await confirmedTicket.check()
  await page.getByRole('button', { name: 'Cancel selected tickets' }).click()
  await expect(page.getByRole('status')).toContainText('Refund SUCCEEDED')
  await expect(page.getByRole('checkbox', { name: /REFUNDED$/ })).toBeDisabled()

  await page.getByRole('button', { name: 'Events' }).click()
  await page.getByLabel('Search events').fill('Lockstep Demo')
  await page.getByRole('button', { name: 'Search' }).click()
  await page.locator('article.event-card').filter({ hasText: 'Lockstep Demo' })
    .getByRole('button', { name: 'View seats' }).click()
  await expect(page.getByRole('button', { name: availableSeatLabel!, exact: true }))
    .toBeEnabled()
})

test('customer session survives refresh and browser Back', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
  await page.goto('about:blank')
  await page.goBack()
  await expect(page.getByRole('heading', { name: 'Choose an event.' })).toBeVisible()
})

test('admin session survives refresh and browser Back', async ({ page }) => {
  test.skip(!adminPassword, 'Set a live or demo admin password to run this scenario.')
  if (!adminPassword) return
  await page.goto('/')
  await page.getByLabel('Email').fill(adminEmail)
  await page.getByLabel('Password').fill(adminPassword)
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('heading', { name: 'Events & reports' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Events & reports' })).toBeVisible()
  await page.goto('about:blank')
  await page.goBack()
  await expect(page.getByRole('heading', { name: 'Events & reports' })).toBeVisible()
})
