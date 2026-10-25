import { useState } from 'react'

interface PasswordDialogProps {
  fileName: string
  /** The password sent last time was refused. */
  wrong: boolean
  onSubmit: (password: string) => void
  onCancel: () => void
}

/** Asks for the password of an encrypted PDF while it is being opened. */
export function PasswordDialog({ fileName, wrong, onSubmit, onCancel }: PasswordDialogProps) {
  const [password, setPassword] = useState('')

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <form
        className="find-dialog password-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="PDF 암호 입력"
        onSubmit={(e) => {
          e.preventDefault()
          if (password !== '') onSubmit(password)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') onCancel()
        }}
      >
        <div className="find-head">
          <b>암호가 걸린 PDF</b>
          <button type="button" onClick={onCancel} aria-label="닫기">
            ✕
          </button>
        </div>
        <p>
          <b>{fileName}</b> 파일을 열려면 암호가 필요합니다.
        </p>
        {wrong && <p className="password-wrong">암호가 맞지 않습니다. 다시 입력해 주세요.</p>}
        <input
          type="password"
          aria-label="암호"
          autoFocus
          autoComplete="off"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <p className="password-note">
          암호를 풀어서 편집합니다. 내보낸 PDF에는 암호가 걸려 있지 않습니다.
        </p>
        <div className="find-actions">
          <button type="button" onClick={onCancel}>
            열지 않기
          </button>
          <button type="submit" className="primary" disabled={password === ''}>
            열기
          </button>
        </div>
      </form>
    </div>
  )
}
