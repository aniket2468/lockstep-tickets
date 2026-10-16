import { ReactNode } from 'react'

export function ErrorPanel({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="notice error-panel" role="alert">
      <p>{message}</p>
      {retry && <button type="button" onClick={retry}>Try again</button>}
    </div>
  )
}

export function SuccessPanel({ children }: { children: ReactNode }) {
  return <div className="notice success" role="status">{children}</div>
}

export function Loading({ children = 'Loading…' }: { children?: ReactNode }) {
  return <p className="loading" role="status">{children}</p>
}

export function StatusBadge({ children }: { children: ReactNode }) {
  return <span className="status-badge">{children}</span>
}
