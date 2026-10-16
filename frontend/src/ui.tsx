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

export function OutcomeFields({ prefix = '' }: { prefix?: string }) {
  return (
    <div className="field-pair">
      <label>
        Mock provider result
        <select name={`${prefix}Outcome`} defaultValue="SUCCESS">
          <option value="SUCCESS">Success</option>
          <option value="DECLINE">Decline</option>
          <option value="TIMEOUT">Timeout</option>
          <option value="UNKNOWN">Unknown</option>
        </select>
      </label>
      <label>
        Status lookup result
        <select name={`${prefix}Lookup`} defaultValue="UNKNOWN">
          <option value="UNKNOWN">Unknown</option>
          <option value="SUCCESS">Success</option>
          <option value="DECLINE">Decline</option>
        </select>
      </label>
    </div>
  )
}

export function StatusBadge({ children }: { children: ReactNode }) {
  return <span className="status-badge">{children}</span>
}
