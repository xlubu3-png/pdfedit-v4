import { useEffect, useRef, useState } from 'react'
import { createEditor } from '@rhwp/editor'
import type { RhwpEditor } from '@rhwp/editor'
import { ArrowLeft, FileDown } from 'lucide-react'
import { downloadBlob } from '../download'
import { errorText } from '../errors'
import { formatOf, HWP_STUDIO_URL, savedName } from '../hwp'
import type { HwpFormat } from '../hwp'

interface HwpEditorProps {
  /** The document to open; null starts a new, empty one (saved as "새 문서.hwpx"). */
  file: File | null
  onClose: () => void
}

/** The name a new document is saved under until the user renames the saved file. */
export const NEW_DOCUMENT_NAME = '새 문서.hwpx'

function download(bytes: Uint8Array, fileName: string) {
  downloadBlob(new Blob([new Uint8Array(bytes)], { type: 'application/octet-stream' }), fileName)
}

/** How long the studio is given to draw the empty document it starts with. */
const STARTUP_WAIT_MS = 5000
/** How long after a document was handed over its pages are looked for, and how often it is handed over again. */
const DRAW_WAIT_MS = 500
const LOAD_TRIES = 5

/**
 * Whether the studio (served by this app, so its page can be looked into) has pages drawn: waits for them up to
 * `withinMs`.
 */
async function pagesDrawn(container: HTMLElement, withinMs: number): Promise<boolean> {
  const drawn = (): boolean => {
    try {
      return !!container.querySelector('iframe')?.contentDocument?.querySelector('canvas.document-page-canvas')
    } catch {
      return false
    }
  }
  const until = Date.now() + withinMs
  while (!drawn()) {
    if (Date.now() >= until) return false
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  return true
}

/**
 * A Hangul document (HWP or HWPX) opened in rhwp-studio, the open-source HWP editor the app serves itself:
 * the studio's own menus and toolbars edit it, and the buttons here save it back as HWPX or HWP. Nothing of
 * the document leaves the PC.
 */
export function HwpEditor({ file, onClose }: HwpEditorProps) {
  const fileName = file?.name ?? NEW_DOCUMENT_NAME
  const host = useRef<HTMLDivElement>(null)
  const editor = useRef<RhwpEditor | null>(null)
  const [pageCount, setPageCount] = useState<number | null>(null)
  const [status, setStatus] = useState<string | null>('여는 중…')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    const container = host.current
    if (!container) return
    let cancelled = false
    let opened: RhwpEditor | null = null
    void (async () => {
      try {
        opened = await createEditor(container, { studioUrl: HWP_STUDIO_URL, renderer: 'canvas2d' })
        if (cancelled) {
          opened.destroy()
          return
        }
        editor.current = opened
        // The studio (0.9.0 on) opens an empty document by itself as it starts. A file handed over while that
        // one is still being set up is loaded but its pages are never drawn: wait until the empty one is.
        await pagesDrawn(container, STARTUP_WAIT_MS)
        if (cancelled) return
        if (file) {
          const bytes = await file.arrayBuffer()
          let loaded = await opened.loadFile(bytes.slice(0), file.name)
          // The studio has drawn the pages by the time it answers. When it has not (0.9.0: its choosing of how to
          // draw can cut in on the loading, and the pages are then left out without a word), it is handed over
          // again.
          for (let tries = 1; tries < LOAD_TRIES && !cancelled && !(await pagesDrawn(container, DRAW_WAIT_MS)); tries++) {
            loaded = await opened.loadFile(bytes.slice(0), file.name)
          }
          if (cancelled) return
          setPageCount(loaded.pageCount)
        } else {
          // The empty document the studio opened by itself is the new one; an older studio is asked for it
          // ("새 문서", Alt+N), and makes it a moment after it is asked.
          if ((await opened.pageCount().catch(() => 0)) === 0) await opened.commands.execute('file:new-doc')
          let pages = 0
          for (let tries = 0; tries < 50 && pages === 0 && !cancelled; tries++) {
            pages = await opened.pageCount().catch(() => 0)
            if (pages === 0) await new Promise((resolve) => setTimeout(resolve, 100))
          }
          if (cancelled) return
          if (pages === 0) throw new Error('새 문서를 만들지 못했습니다. 다시 시도해 주세요.')
          setPageCount(pages)
        }
        setStatus(null)
      } catch (e) {
        if (cancelled) return
        setStatus(null)
        // The editor's own reason is in English: kept in the browser console, the screen says it in Korean.
        console.error(e)
        const why = errorText(e, '')
        setError(
          !file && why
            ? why
            : why
            ? `한글 문서를 열지 못했습니다: ${why}`
            : '한글 문서를 열지 못했습니다. 파일이 손상되었거나 이 편집기가 읽지 못하는 형식일 수 있습니다.',
        )
      }
    })()
    return () => {
      cancelled = true
      opened?.destroy()
      editor.current = null
    }
  }, [file])

  async function save(format: HwpFormat) {
    const current = editor.current
    if (!current || saving) return
    setSaving(true)
    setError(null)
    try {
      const name = savedName(fileName, format)
      download(format === 'hwpx' ? await current.exportHwpx() : await current.exportHwp(), name)
      // The studio keeps a recovery copy until it is told the document was saved.
      await current.notifySaved(name).catch(() => undefined)
      setStatus(`${name}(으)로 저장했습니다`)
    } catch (e) {
      console.error(e)
      const why = errorText(e, '')
      setError(why ? `저장하지 못했습니다: ${why}` : '저장하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setSaving(false)
    }
  }

  async function close() {
    const current = editor.current
    if (current) {
      const dirty = await current.commands
        .context()
        .then((context) => context.isDirty === true)
        .catch(() => false)
      if (dirty && !window.confirm('저장하지 않은 수정 내용이 있습니다. 저장하지 않고 닫을까요?')) return
    }
    onClose()
  }

  const own = formatOf(fileName)
  const other: HwpFormat = own === 'hwp' ? 'hwpx' : 'hwp'
  const ready = pageCount !== null

  return (
    <div className="te-overlay hwp-overlay" role="dialog" aria-modal="true" aria-label="한글 문서 편집">
      <header className="te-header rb-header">
        <div className="rb-top hwp-top">
          <button type="button" className="rb-back" onClick={() => void close()} title="페이지 목록으로 돌아갑니다">
            <ArrowLeft size={16} aria-hidden="true" /> 페이지 목록
          </button>
          <span className="te-title" title={fileName}>
            한글 문서 · {file ? fileName : '새 문서'}
            {ready && ` · ${pageCount}쪽`}
          </span>
          <span className="te-spacer" />
          <span className="te-status" aria-live="polite">
            {status}
          </span>
          <button
            type="button"
            onClick={() => void save(other)}
            disabled={!ready || saving}
            title={`${other.toUpperCase()} 형식으로 내려받습니다`}
          >
            <FileDown size={16} aria-hidden="true" /> {other.toUpperCase()}로 저장
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void save(own)}
            disabled={!ready || saving}
            title={`원래 형식(${own.toUpperCase()})으로 내려받습니다`}
          >
            <FileDown size={16} aria-hidden="true" /> {own.toUpperCase()}로 저장
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
      <div className="hwp-host" ref={host} data-testid="hwp-host" />
    </div>
  )
}
