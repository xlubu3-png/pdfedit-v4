import { useEffect, useRef, useState } from 'react'
import { createEditor } from '@rhwp/editor'
import type { RhwpEditor } from '@rhwp/editor'
import { ArrowLeft, FileDown } from 'lucide-react'
import { formatOf, HWP_STUDIO_URL, savedName } from '../hwp'
import type { HwpFormat } from '../hwp'

interface HwpEditorProps {
  file: File
  onClose: () => void
}

function download(bytes: Uint8Array, fileName: string) {
  const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'application/octet-stream' }))
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * A Hangul document (HWP or HWPX) opened in rhwp-studio, the open-source HWP editor the app serves itself:
 * the studio's own menus and toolbars edit it, and the buttons here save it back as HWPX or HWP. Nothing of
 * the document leaves the PC.
 */
export function HwpEditor({ file, onClose }: HwpEditorProps) {
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
        const loaded = await opened.loadFile(await file.arrayBuffer(), file.name)
        if (cancelled) return
        setPageCount(loaded.pageCount)
        setStatus(null)
      } catch (e) {
        if (cancelled) return
        setStatus(null)
        setError(`한글 문서를 열지 못했습니다: ${e instanceof Error ? e.message : String(e)}`)
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
      const name = savedName(file.name, format)
      download(format === 'hwpx' ? await current.exportHwpx() : await current.exportHwp(), name)
      // The studio keeps a recovery copy until it is told the document was saved.
      await current.notifySaved(name).catch(() => undefined)
      setStatus(`${name}(으)로 저장했습니다`)
    } catch (e) {
      setError(`저장하지 못했습니다: ${e instanceof Error ? e.message : String(e)}`)
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

  const own = formatOf(file.name)
  const other: HwpFormat = own === 'hwp' ? 'hwpx' : 'hwp'
  const ready = pageCount !== null

  return (
    <div className="te-overlay hwp-overlay" role="dialog" aria-modal="true" aria-label="한글 문서 편집">
      <header className="te-header rb-header">
        <div className="rb-top hwp-top">
          <button type="button" className="rb-back" onClick={() => void close()} title="페이지 목록으로 돌아갑니다">
            <ArrowLeft size={16} aria-hidden="true" /> 페이지 목록
          </button>
          <span className="te-title" title={file.name}>
            한글 문서 · {file.name}
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
