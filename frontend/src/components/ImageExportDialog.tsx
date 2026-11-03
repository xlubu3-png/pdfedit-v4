import { useState } from 'react'
import { IMAGE_SHARPNESS } from '../imageExport'
import type { ImageChoice, ImageFormat } from '../imageExport'

interface ImageExportDialogProps {
  /** How many pages become pictures. */
  pageCount: number
  /** Whether those are the picked pages (else all of the list). */
  picked: boolean
  /** Some of the pages came from a password-protected document: pictures cannot be locked. */
  fromProtected: boolean
  initial: ImageChoice
  onExport: (choice: ImageChoice) => void
  onCancel: () => void
}

const FORMATS: { format: ImageFormat; label: string; note: string }[] = [
  { format: 'jpg', label: 'JPG', note: '파일이 작아 보내기 좋습니다' },
  { format: 'png', label: 'PNG', note: '글자 테두리가 또렷하지만 파일이 큽니다' },
]

/** Asks how the pages are saved as pictures: the kind of file and how sharp. */
export function ImageExportDialog({ pageCount, picked, fromProtected, initial, onExport, onCancel }: ImageExportDialogProps) {
  const [format, setFormat] = useState<ImageFormat>(initial.format)
  const [dpi, setDpi] = useState(initial.dpi)

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <form
        className="find-dialog password-dialog image-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="사진으로 내보내기"
        onSubmit={(e) => {
          e.preventDefault()
          onExport({ format, dpi })
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') onCancel()
        }}
      >
        <div className="find-head">
          <b>사진으로 내보내기</b>
          <button type="button" onClick={onCancel} aria-label="닫기">
            ✕
          </button>
        </div>
        <p>
          {picked ? `선택한 ${pageCount}쪽` : pageCount === 1 ? '이 페이지' : `모든 페이지(${pageCount}쪽)`}를 고친 내용·가림
          영역째로 사진 파일로 저장합니다.{' '}
          {pageCount === 1 ? '사진 한 장으로 내려받습니다.' : '쪽마다 한 장씩, 번호를 붙여 ZIP 파일 하나로 내려받습니다.'}
        </p>
        <fieldset className="image-options">
          <legend>파일 형식</legend>
          {FORMATS.map((f) => (
            <label key={f.format} className="find-check">
              <input type="radio" name="image-format" checked={format === f.format} onChange={() => setFormat(f.format)} />
              <b>{f.label}</b>
              <span className="image-note">{f.note}</span>
            </label>
          ))}
        </fieldset>
        <fieldset className="image-options">
          <legend>선명도</legend>
          {IMAGE_SHARPNESS.map((s) => (
            <label key={s.dpi} className="find-check">
              <input type="radio" name="image-dpi" checked={dpi === s.dpi} onChange={() => setDpi(s.dpi)} />
              <b>{s.label}</b>
              <span className="image-note">
                {s.dpi}dpi · {s.note}
              </span>
            </label>
          ))}
        </fieldset>
        {fromProtected && <p className="password-note">사진 파일에는 암호를 걸 수 없습니다. 보관·전달에 주의해 주세요.</p>}
        <div className="find-actions">
          <button type="button" onClick={onCancel}>
            취소
          </button>
          <button type="submit" className="primary">
            사진으로 저장
          </button>
        </div>
      </form>
    </div>
  )
}
