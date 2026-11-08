import { useState } from 'react'
import { errorText } from '../errors'
import type { AppSettings } from '../types'

interface ScanSettingsDialogProps {
  settings: AppSettings
  onSave: (settings: AppSettings) => Promise<void>
  onClose: () => void
}

/** What recognizing a scan's text does besides reading it. */
export function ScanSettingsDialog({ settings, onSave, onClose }: ScanSettingsDialogProps) {
  const [straighten, setStraighten] = useState(settings.straightenOnOcr !== false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const changed = straighten !== (settings.straightenOnOcr !== false)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await onSave({ ...settings, straightenOnOcr: straighten })
      onClose()
    } catch (e) {
      setError(errorText(e, '저장하지 못했습니다'))
      setBusy(false)
    }
  }

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="find-dialog retention-dialog" role="dialog" aria-modal="true" aria-label="글자 인식 설정">
        <div className="find-head">
          <b>글자 인식 설정</b>
          <button type="button" onClick={onClose} aria-label="닫기" disabled={busy}>
            ✕
          </button>
        </div>
        <label className="check-row">
          <input type="checkbox" checked={straighten} onChange={(e) => setStraighten(e.target.checked)} disabled={busy} />
          글자 인식할 때 스캔 쪽의 방향·기울기를 먼저 바로잡기
        </label>
        <p>
          뒤집히거나 옆으로 누운 쪽은 바로 돌리고, 비스듬히 스캔된 쪽(0.25°~5°)은 바로 세운 뒤 읽어 인식이 더 정확해지고,
          글자 선택·엑셀 저장·고쳐 쓰기도 줄에 맞게 됩니다. 쪽 목록에서 직접 돌려 둔 쪽은 방향을 그대로 둡니다. 쪽 그림은 다시 압축하지 않아 화질·용량이 그대로이고, Ctrl+Z로 되돌릴 수 있습니다. 글자로 된 쪽은 건드리지
          않습니다.
        </p>
        {error && <p className="find-message error">{error}</p>}
        <div className="find-actions">
          <button type="button" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className="primary" onClick={() => void save()} disabled={busy || !changed}>
            저장
          </button>
        </div>
      </div>
    </div>
  )
}
