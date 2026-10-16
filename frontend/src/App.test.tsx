import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { App } from './App'

const event = {
  id: 'b23fec6f-24b8-44bb-b45f-65fc3a74ca02',
  name: 'Night at the Symphony',
  state: 'ON_SALE',
  startsAt: '2030-06-10T23:30:00Z',
  timeZone: 'America/Indiana/Indianapolis',
  venue: { name: 'Lockstep Hall', address: '1 Test Way' },
  fromPrice: { amountMinor: 5000, currency: 'USD' },
}

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }))
}

async function signIn() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'customer@example.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse battery staple' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await screen.findByRole('heading', { name: 'Choose an event.' })
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('App', () => {
  it('starts with an accessible sign-in form and password protection guidance', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Find your next seat.' })).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
    expect(screen.getByText(/never stored in this browser/i)).toBeInTheDocument()
  })

  it('authenticates and loads the event catalog without persisting the token', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response({ accessToken: 'secret-token', account: { email: 'customer@example.com' } }))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))

    render(<App />)
    await signIn()

    expect(await screen.findByRole('heading', { name: event.name })).toBeInTheDocument()
    expect(setItem).not.toHaveBeenCalled()
    const catalogRequest = vi.mocked(fetch).mock.calls[1]
    expect(new Headers(catalogRequest[1]?.headers).get('Authorization')).toBe('Bearer secret-token')
  })

  it('shows the stable API error and request reference when login fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => response({
      error: { message: 'The credentials are invalid.', requestId: 'request-123' },
    }, 401))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'customer@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The credentials are invalid. Reference: request-123.')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('searches with encoded literal input and renders an empty result', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response({ accessToken: 'token', account: { email: 'customer@example.com' } }))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response({ items: [], limit: 20, offset: 0, hasMore: false }))
    render(<App />)
    await signIn()
    await screen.findByRole('heading', { name: event.name })

    fireEvent.change(screen.getByLabelText('Search events'), { target: { value: "100%_' OR 1=1" } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('No events match your search.')).toBeInTheDocument()
    const searchRequest = vi.mocked(fetch).mock.calls[2][0]
    const searchUrl = typeof searchRequest === 'string'
      ? searchRequest
      : searchRequest instanceof URL
        ? searchRequest.href
        : searchRequest.url
    expect(searchUrl)
      .toContain('query=100%25_%27+OR+1%3D1')
  })

  it('loads seat detail and disables unavailable inventory', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response({ accessToken: 'token', account: { email: 'customer@example.com' } }))
      .mockImplementationOnce(() => response({ items: [event], limit: 20, offset: 0, hasMore: false }))
      .mockImplementationOnce(() => response({
        ...event,
        serviceFee: { amountMinor: 250, currency: 'USD' },
        seats: [
          { id: '1', sectionName: 'Orchestra', rowLabel: 'A', seatNumber: '1', accessible: false, availability: 'AVAILABLE', priceTier: 'Standard', price: event.fromPrice },
          { id: '2', sectionName: 'Orchestra', rowLabel: 'A', seatNumber: '2', accessible: true, availability: 'UNAVAILABLE', priceTier: 'Standard', price: event.fromPrice },
        ],
      }))
    render(<App />)
    await signIn()
    fireEvent.click(await screen.findByRole('button', { name: 'View seats' }))

    expect(await screen.findByRole('heading', { name: event.name })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /seat 1, available/i })).toBeEnabled()
    expect(screen.getByRole('button', { name: /seat 2, unavailable/i })).toBeDisabled()
    expect(screen.getByText('Accessible')).toBeInTheDocument()
  })

  it('returns to sign-in when an authenticated request expires', async () => {
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => response({ accessToken: 'expired-token', account: { email: 'customer@example.com' } }))
      .mockImplementationOnce(() => response({ error: { message: 'Authentication is required.' } }, 401))
    render(<App />)
    await signIn()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Find your next seat.' })).toBeInTheDocument())
  })
})
