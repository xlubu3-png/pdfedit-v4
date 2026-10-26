import { useState } from 'react'

/** The periods offered; the current one is added when it is none of them (set elsewhere). */
const CHOICES = [7, 30, 90, 180, 365]

interface RetentionDialogProps {
  days: number
  onSave: (days: number) => Promise<void>
  onClose: () => void
}

/** How long the documents the user opened are kept when nobody opens them again. */
export function RetentionDialog({ days, onSave, onClose }: RetentionDialogProps) {
  const [chosen, setChosen] = useState(days)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const choices = CHOICES.includes(days) ? CHOICES : [...CHOICES, days].sort((a, b) => a - b)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await onSave(chosen)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장하지 못했습니다')
      setBusy(false)
    }
  }

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="find-dialog retention-dialog" role="dialog" aria-modal="true" aria-label="작업 보관 기간">
        <div className="find-head">
          <b>작업 보관 기간</b>
          <button type="button" onClick={onClose} aria-label="닫기" disabled={busy}>
            ✕
          </button>
        </div>
        <p>
          올린 문서와 고친 내용은 이 PC의 데이터 폴더에 보관되어, 앱을 다시 켜도 이어서 작업할 수 있습니다. 정한 기간 동안 한 번도 열지
          않은 문서는 자동으로 정리됩니다. <b>원본 파일은 지워지지 않습니다.</b>
        </p>
        <label>
          열지 않은 문서를 보관할 기간
          <select value={chosen} onChange={(e) => setChosen(Number(e.target.value))} disabled={busy}>
            {choices.map((d) => (
              <option key={d} value={d}>
                {d === 365 ? '1년 (365일)' : `${d}일`}
              </option>
            ))}
          </select>
        </label>
        {chosen < days && (
          <p className="retention-warn">
            지금보다 짧게 하면, {chosen}일 넘게 열지 않은 문서는 몇 분 안에 정리됩니다.
          </p>
        )}
        {error && <p className="find-message error">{error}</p>}
        <div className="find-actions">
          <button type="button" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className="primary" onClick={() => void save()} disabled={busy || chosen === days}>
            저장
          </button>
        </div>
      </div>
    </div>
  )
}
