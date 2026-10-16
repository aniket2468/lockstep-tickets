import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { App } from './App'

describe('App', () => {
  it('identifies the current delivery state', () => {
    render(<App />)

    expect(
      screen.getByRole('heading', { name: /every seat accounted for/i }),
    ).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/foundation is ready/i)
  })
})

