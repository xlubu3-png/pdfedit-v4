import { useState } from 'react'

/** What is done to each file, and what is saved of it. */
export interface BatchChoice {
  /** The files (as the page list names them) to do it to. */
  files: string[]
  /** Recognize the text of their scanned pages (set straight first, as the settings say). */
  ocr: boolean
  /** Store their pictures smaller (용량 줄이기) in the PDF saved. */
  shrink: boolean
  pdf: boolean
  excel: boolean
}

interface BatchDialogProps {
  /** The files open in the page list, with how many pages of each it has. */
  files: { name: string; pages: number }[]
  onRun: (choice: BatchChoice) => void
  onClose: () => void
}

/** 파일마다 처리: the same work done to several files, each saved on its own (in one ZIP when there are several). */
export function BatchDialog({ files, onRun, onClose }: BatchDialogProps) {
  const [chosen, setChosen] = useState<Set<string>>(() => new Set(files.map((f) => f.name)))
  const [ocr, setOcr] = useState(true)
  const [shrink, setShrink] = useState(false)
  const [pdf, setPdf] = useState(true)
  const [excel, setExcel] = useState(false)
  const ready = chosen.size > 0 && (pdf || excel)

  function toggle(name: string) {
    setChosen((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="find-dialog retention-dialog batch-dialog" role="dialog" aria-modal="true" aria-label="파일마다 처리">
        <div className="find-head">
          <b>파일마다 처리</b>
          <button type="button" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>
        <p>연 파일마다 같은 작업을 하고, 파일마다 따로 저장합니다(여러 개면 ZIP 하나로 내려받음).</p>
        <fieldset>
          <legend>파일 ({chosen.size}/{files.length})</legend>
          {files.map((f) => (
            <label key={f.name} className="check-row">
              <input type="checkbox" checked={chosen.has(f.name)} onChange={() => toggle(f.name)} />
              {f.name} <span className="batch-pages">{f.pages}쪽</span>
            </label>
          ))}
        </fieldset>
        <fieldset>
          <legend>할 일</legend>
          <label className="check-row">
            <input type="checkbox" checked={ocr} onChange={(e) => setOcr(e.target.checked)} />
            스캔한 쪽 글자 인식 (방향·기울기 바로잡기 포함)
          </label>
          <label className="check-row">
            <input type="checkbox" checked={shrink} onChange={(e) => setShrink(e.target.checked)} disabled={!pdf} />
            PDF 용량 줄이기
          </label>
        </fieldset>
        <fieldset>
          <legend>저장</legend>
          <label className="check-row">
            <input type="checkbox" checked={pdf} onChange={(e) => setPdf(e.target.checked)} />
            PDF 파일
          </label>
          <label className="check-row">
            <input type="checkbox" checked={excel} onChange={(e) => setExcel(e.target.checked)} />
            엑셀 파일 (쪽마다 시트)
          </label>
        </fieldset>
        <p className="batch-note">암호가 걸려 있던 파일도 암호 없이 저장됩니다. 하나씩 암호를 걸려면 내보내기를 쓰세요.</p>
        <div className="find-actions">
          <button type="button" onClick={onClose}>
            취소
          </button>
          <button
            type="button"
            className="primary"
            disabled={!ready}
            onClick={() => onRun({ files: files.map((f) => f.name).filter((n) => chosen.has(n)), ocr, shrink: pdf && shrink, pdf, excel })}
          >
            실행
          </button>
        </div>
      </div>
    </div>
  )
}
