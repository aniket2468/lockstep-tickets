import { ApiError } from './api'

export function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const reference = error.requestId ? ` Reference: ${error.requestId}.` : ''
    return `${error.message}${reference}`
  }
  return 'We could not reach the ticket service. Please try again.'
}

export function isAbort(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function formatMinor(amountMinor: number, currency: string) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amountMinor / 100)
}

export function formatDate(value: string, timeZone?: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).format(new Date(value))
}

export function newIdempotencyKey() {
  return `ui-${crypto.randomUUID()}`
}

export function formText(data: FormData, name: string) {
  const value = data.get(name)
  return typeof value === 'string' ? value : ''
}
