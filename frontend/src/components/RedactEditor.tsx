import { useEffect, useRef, useState } from 'react'
import { fetchPageText, fetchRedactions, previewUrl, saveRedactions } from '../api'
import { newAreaId } from '../redaction'
import type { PageItem, Redaction } from '../types'

interface RedactEditorProps {
  pages: PageItem[]
  startKey: string
  onClose: () => void
  /** The page got other areas: its thumbnail is drawn again. */
  onChanged: (page: { documentId: string; pageIndex: number }) => void
}

interface PageBox {
  pageWidth: number
  pageHeight: number
  originX: number
  originY: number
}

/** A drag shorter than this (screen pixels) is a click, not an area. */
const MIN_DRAG = 4
const PREVIEW_DPI = 110

/**
 * A page with its redaction areas: dragging on the page draws a new one, a click picks one and ✕ or
 * Delete removes it. Every change is saved at once; the areas take effect on export.
 */
export function RedactEditor({ pages, startKey, onClose, onChanged }: RedactEditorProps) {
  const [index, setIndex] = useState(() => Math.max(0, pages.findIndex((p) => p.key === startKey)))
  const page = pages[Math.min(index, pages.length - 1)]
  const [box, setBox] = useState<PageBox | null>(null)
  const [areas, setAreas] = useState<Redaction[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [drag, setDrag] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const layer = useRef<HTMLDivElement>(null)

  // Only another page is loaded again; a saved change (which redraws the thumbnails) is not.
  const documentId = page?.documentId
  const pageIndex = page?.pageIndex
  useEffect(() => {
    if (documentId === undefined || pageIndex === undefined) return
    let cancelled = false
    setBox(null)
    setSelected(null)
    setStatus(null)
    setError(null)
    Promise.all([fetchPageText(documentId, pageIndex), fetchRedactions(documentId, pageIndex)])
      .then(([text, saved]) => {
        if (cancelled) return
        setBox({ pageWidth: text.pageWidth, pageHeight: text.pageHeight, originX: text.originX, originY: text.originY })
        setAreas(saved)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '페이지를 불러오지 못했습니다'))
    return () => {
      cancelled = true
    }
  }, [documentId, pageIndex])

  const width = Math.min(1000, Math.max(320, window.innerWidth - 80))
  const scale = box ? width / box.pageWidth : 0

  async function commit(next: Redaction[]) {
    const before = areas
    setAreas(next)
    setStatus('저장 중…')
    try {
      await saveRedactions(page.documentId, page.pageIndex, next)
      onChanged({ documentId: page.documentId, pageIndex: page.pageIndex })
      setStatus(`가림 영역 ${next.length}곳 · 저장됨`)
    } catch (e) {
      setAreas(before)
      setStatus(null)
      setError(e instanceof Error ? e.message : '저장하지 못했습니다')
    }
  }

  const remove = (id: string) => {
    setSelected(null)
    void commit(areas.filter((a) => a.id !== id))
  }

  /** The pointer position inside the page, in screen pixels. */
  const local = (e: React.PointerEvent) => {
    const r = layer.current!.getBoundingClientRect()
    return { x: Math.min(Math.max(e.clientX - r.left, 0), r.width), y: Math.min(Math.max(e.clientY - r.top, 0), r.height) }
  }

  const keys = useRef<(e: KeyboardEvent) => void>(() => {})
  useEffect(() => {
    keys.current = (e) => {
      if (e.target instanceof HTMLInputElement) return
      if ((e.key === 'Delete' || e.key === 'Backspace') && selected) {
        e.preventDefault()
        remove(selected)
      } else if (e.key === 'Escape') {
        if (selected) setSelected(null)
        else onClose()
      }
    }
  })
  useEffect(() => {
    const listener = (e: KeyboardEvent) => keys.current(e)
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  if (!page) return null

  return (
    <div className="te-overlay rd-overlay" role="dialog" aria-modal="true" aria-label="가림 영역 편집">
      <header className="te-header">
        <button type="button" onClick={onClose}>
          ← 페이지 목록
        </button>
        <span className="te-title" title={page.sourceFileName}>
          가림 영역 · {page.sourceFileName} · {page.pageIndex + 1}쪽
        </span>
        <span className="te-spacer" />
        <span className="te-status" aria-live="polite">
          {status}
        </span>
        <span className="te-group">
          <button type="button" onClick={() => setIndex((i) => i - 1)} disabled={index <= 0} title="이전 페이지">
            ◀
          </button>
          <span className="te-sub">
            {index + 1} / {pages.length}
          </span>
          <button type="button" onClick={() => setIndex((i) => i + 1)} disabled={index >= pages.length - 1} title="다음 페이지">
            ▶
          </button>
        </span>
        <button type="button" onClick={() => void commit([])} disabled={areas.length === 0}>
          이 페이지 가림 지우기
        </button>
        <button type="button" className="primary" onClick={onClose}>
          완료
        </button>
      </header>
      <p className="rd-help">
        페이지 위를 <b>끌어서</b> 가릴 곳을 그리세요. 상자를 누르고 <kbd>Delete</kbd> 또는 ✕로 지웁니다. 상자 아래의 글자·그림은 PDF로
        내보낼 때 실제로 지워집니다.
      </p>
      {error && (
        <div className="error-banner te-error">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}
      <main className="te-main">
        {!box && !error && <p className="te-notice">불러오는 중…</p>}
        {box && (
          <div className="te-canvas">
            <img
              src={previewUrl(page.documentId, page.pageIndex, page.textRevision, PREVIEW_DPI)}
              alt={`${page.sourceFileName} ${page.pageIndex + 1}쪽`}
              style={{ width }}
              draggable={false}
            />
            <div
              className="rd-layer"
              ref={layer}
              data-testid="redact-layer"
              onPointerDown={(e) => {
                if (e.button !== 0 || e.target !== e.currentTarget) return
                e.currentTarget.setPointerCapture(e.pointerId)
                const p = local(e)
                setSelected(null)
                setDrag({ x0: p.x, y0: p.y, x1: p.x, y1: p.y })
              }}
              onPointerMove={(e) => {
                if (!drag) return
                const p = local(e)
                setDrag({ ...drag, x1: p.x, y1: p.y })
              }}
              onPointerUp={() => {
                const d = drag
                setDrag(null)
                if (!d || !box) return
                const left = Math.min(d.x0, d.x1)
                const top = Math.min(d.y0, d.y1)
                const w = Math.abs(d.x1 - d.x0)
                const h = Math.abs(d.y1 - d.y0)
                if (w < MIN_DRAG || h < MIN_DRAG) return
                const area: Redaction = {
                  id: newAreaId(),
                  x: round(box.originX + left / scale),
                  y: round(box.originY + box.pageHeight - (top + h) / scale),
                  width: round(w / scale),
                  height: round(h / scale),
                }
                setSelected(area.id)
                void commit([...areas, area])
              }}
              onPointerCancel={() => setDrag(null)}
            >
              {areas.map((a) => (
                <div
                  key={a.id}
                  className={`rd-box${selected === a.id ? ' selected' : ''}`}
                  data-testid="redact-box"
                  style={{
                    left: (a.x - box.originX) * scale,
                    top: (box.pageHeight - (a.y + a.height - box.originY)) * scale,
                    width: a.width * scale,
                    height: a.height * scale,
                  }}
                  onPointerDown={(e) => {
                    e.stopPropagation()
                    setSelected(a.id)
                  }}
                >
                  <button
                    type="button"
                    className="rd-remove"
                    aria-label="가림 영역 지우기"
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={() => remove(a.id)}
                  >
                    ✕
                  </button>
                </div>
              ))}
              {drag && (
                <div
                  className="rd-box drawing"
                  style={{
                    left: Math.min(drag.x0, drag.x1),
                    top: Math.min(drag.y0, drag.y1),
                    width: Math.abs(drag.x1 - drag.x0),
                    height: Math.abs(drag.y1 - drag.y0),
                  }}
                />
              )}
            </div>
          </div>
        )}
      </main>
    </div>
  )
}

const round = (v: number) => Math.round(v * 100) / 100
