import { useState } from 'react'

interface LockDialogProps {
  /** Some of the pages come from a document that was opened with its password. */
  fromProtected: boolean
  onLock: (password: string) => void
  /** Export without a password; only offered for pages that came protected. */
  onPlain: () => void
  onCancel: () => void
}

/** Asks for the password the exported PDF is locked with. */
export function LockDialog({ fromProtected, onLock, onPlain, onCancel }: LockDialogProps) {
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const differs = again !== '' && again !== password
  const ready = password !== '' && again === password

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <form
        className="find-dialog password-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="내보낼 PDF에 암호 걸기"
        onSubmit={(e) => {
          e.preventDefault()
          if (ready) onLock(password)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') onCancel()
        }}
      >
        <div className="find-head">
          <b>암호 걸어 내보내기</b>
          <button type="button" onClick={onCancel} aria-label="닫기">
            ✕
          </button>
        </div>
        {fromProtected ? (
          <p>
            원래 문서에 암호가 걸려 있었습니다. 내보내는 PDF에도 암호를 걸면 인쇄·복사 같은 원래 문서의 제한도 그대로
            적용됩니다.
          </p>
        ) : (
          <p>내보내는 PDF를 열 때 이 암호를 물어봅니다.</p>
        )}
        <input
          type="password"
          aria-label="암호"
          placeholder="암호"
          autoFocus
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <input
          type="password"
          aria-label="암호 확인"
          placeholder="암호 확인"
          autoComplete="new-password"
          value={again}
          onChange={(e) => setAgain(e.target.value)}
        />
        {differs && <p className="password-wrong">두 암호가 다릅니다.</p>}
        <p className="password-note">암호는 이 프로그램에 저장되지 않습니다. 잊어버리면 파일을 열 수 없습니다.</p>
        <div className="find-actions">
          {fromProtected ? (
            <button type="button" onClick={onPlain}>
              암호 없이 내보내기
            </button>
          ) : (
            <button type="button" onClick={onCancel}>
              취소
            </button>
          )}
          <button type="submit" className="primary" disabled={!ready}>
            암호 걸어 내보내기
          </button>
        </div>
      </form>
    </div>
  )
}
