import { ArrowLeft, FileDown } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { downloadBlob } from '../download'
import { errorText } from '../errors'
import type { PolishApi } from '../polish/main'
import { fromBook, toBook } from '../sheetEdit'
import type { Sheet } from '../tableGrid'
import { sheetsToXlsx } from '../xlsx'

/** Where the app serves the 표 다듬기 screen (its own page, see polish.html). */
export const POLISH_URL = '/polish.html'

interface SheetPolishProps {
  /** The sheets made from the pages, as "엑셀로 저장" would write them. */
  sheets: { name: string; sheet: Sheet }[]
  /** The name the workbook is saved under ("견적서.xlsx"). */
  fileName: string
  onClose: () => void
}

/**
 * 표 다듬기: the sheets taken out of the PDF shown as a spreadsheet to be put right before they are saved - a
 * value read wrongly, a formula for the total, a cell merged or coloured - and then saved as the xlsx. The
 * spreadsheet is a page of its own in a frame (its keys and styles stay apart from the app's); nothing leaves the PC.
 */
export function SheetPolish({ sheets, fileName, onClose }: SheetPolishProps) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [ready, setReady] = useState(false)
  const [status, setStatus] = useState<string | null>('여는 중…')
  const [error, setError] = useState<string | null>(null)

  // The frame's page sets `window.polish` once the spreadsheet is up; the sheets are handed to it then.
  useEffect(() => {
    let cancelled = false
    let tries = 0
    const timer = window.setInterval(() => {
      if (cancelled) return
      const polish = api(frame.current)
      if (polish) {
        window.clearInterval(timer)
        const failed = (e: unknown) => {
          if (cancelled) return
          console.error(e)
          setStatus(null)
          setError('표를 여는 중 문제가 생겼습니다. 닫고 다시 시도해 주세요.')
        }
        try {
          polish.load(toBook(sheets, fileName)).then(() => {
            if (cancelled) return
            setReady(true)
            setStatus(null)
          }, failed)
        } catch (e) {
          failed(e)
        }
      } else if (++tries > 300) {
        window.clearInterval(timer)
        setStatus(null)
        setError('표 다듬기 화면을 불러오지 못했습니다. 닫고 다시 시도해 주세요.')
      }
    }, 100)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [sheets, fileName])

  function save() {
    const polish = api(frame.current)
    if (!polish) return
    setError(null)
    try {
      const edited = fromBook(polish.save())
      if (edited.every((s) => s.sheet.cells.length === 0)) {
        setError('저장할 내용이 없습니다.')
        return
      }
      downloadBlob(sheetsToXlsx(edited), fileName)
      polish.saved()
      setStatus(`${fileName}로 저장했습니다`)
    } catch (e) {
      console.error(e)
      setError(errorText(e, '엑셀 파일로 저장하지 못했습니다. 다시 시도해 주세요.'))
    }
  }

  function close() {
    let changed = false
    try {
      changed = api(frame.current)?.changed() ?? false
    } catch {
      // the frame is gone: nothing to lose
    }
    if (changed && !window.confirm('고친 내용을 엑셀 파일로 저장하지 않았습니다. 그대로 닫을까요?')) return
    onClose()
  }

  return (
    <div className="te-overlay polish-overlay" role="dialog" aria-modal="true" aria-label="표 다듬기">
      <header className="te-header rb-header">
        <div className="rb-top hwp-top">
          <button type="button" className="rb-back" onClick={close} title="저장하지 않은 내용이 있으면 물어보고 닫습니다">
            <ArrowLeft size={16} aria-hidden="true" /> 닫기
          </button>
          <span className="te-title" title={fileName}>
            표 다듬기 · {fileName}
          </span>
          <span className="polish-hint">PDF에서 뽑은 표입니다. 값·수식·칸을 고친 뒤 저장하세요. PDF 원본은 바뀌지 않습니다.</span>
          <span className="te-spacer" />
          <span className="te-status" aria-live="polite">
            {status}
          </span>
          <button type="button" className="primary" onClick={save} disabled={!ready} title="지금 보이는 대로 엑셀 파일(.xlsx)로 내려받습니다">
            <FileDown size={16} aria-hidden="true" /> 엑셀로 저장
          </button>
        </div>
      </header>
      {error && (
        <div className="error-banner te-error">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}
      <div className="hwp-host">
        <iframe ref={frame} src={POLISH_URL} title="표 다듬기 화면" data-testid="polish-frame" />
      </div>
    </div>
  )
}

/** The spreadsheet page's own functions, once it is up (the frame is served by this app, so it can be looked into). */
function api(frame: HTMLIFrameElement | null): PolishApi | undefined {
  try {
    return frame?.contentWindow?.polish
  } catch {
    return undefined
  }
}
