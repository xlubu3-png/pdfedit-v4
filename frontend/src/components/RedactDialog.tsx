import { useMemo, useState } from 'react'
import { fetchRedactions, findPersonalInfo, saveRedactions } from '../api'
import { mergeAreas, newAreaId, PERSONAL_KINDS } from '../redaction'
import type { PageItem, PersonalInfoKind, PersonalInfoMatch } from '../types'

interface PageRef {
  documentId: string
  pageIndex: number
}

interface RedactDialogProps {
  pages: PageItem[]
  onClose: () => void
  /** Opens the view in which areas are drawn by hand, on this page. */
  onDraw: (key: string) => void
  /** These pages got other areas: their thumbnails are drawn again. */
  onChanged: (changed: PageRef[]) => void
}

const refKey = (p: PageRef) => `${p.documentId}:${p.pageIndex}`

/**
 * Looks for personal information (and any text) on every page and turns what the user ticks into redaction
 * areas. The areas are only marks until the export, which cuts out what lies under them and checks it.
 */
export function RedactDialog({ pages, onClose, onDraw, onChanged }: RedactDialogProps) {
  const [kinds, setKinds] = useState<Set<PersonalInfoKind>>(
    () => new Set(PERSONAL_KINDS.filter((k) => k.on).map((k) => k.kind)),
  )
  const [term, setTerm] = useState('')
  const [results, setResults] = useState<PersonalInfoMatch[] | null>(null)
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Every page of the list once, even when the same page was put in twice.
  const uniquePages = useMemo(() => {
    const seen = new Map<string, PageRef>()
    for (const p of pages) seen.set(refKey(p), { documentId: p.documentId, pageIndex: p.pageIndex })
    return [...seen.values()]
  }, [pages])

  /** Where the page sits in the list, for "3쪽"; the first place when it is there twice. */
  const pageNumber = (m: PageRef) => pages.findIndex((p) => refKey(p) === refKey(m)) + 1
  const pageKey = (m: PageRef) => pages.find((p) => refKey(p) === refKey(m))?.key

  async function run<T>(work: () => Promise<T>): Promise<T | undefined> {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      return await work()
    } catch (e) {
      setError(e instanceof Error ? e.message : '처리하지 못했습니다')
      return undefined
    } finally {
      setBusy(false)
    }
  }

  async function search() {
    const found = await run(() => findPersonalInfo(uniquePages, [...kinds], term))
    if (!found) return
    setResults(found)
    setPicked(new Set(found.map((_, i) => i)))
    if (found.length === 0) setMessage('찾은 항목이 없습니다.')
  }

  async function redactPicked() {
    if (!results) return
    const chosen = results.filter((_, i) => picked.has(i))
    const byPage = new Map<string, PersonalInfoMatch[]>()
    for (const m of chosen) byPage.set(refKey(m), [...(byPage.get(refKey(m)) ?? []), m])
    const done = await run(async () => {
      const changed: PageRef[] = []
      for (const group of byPage.values()) {
        const { documentId, pageIndex } = group[0]
        const existing = await fetchRedactions(documentId, pageIndex)
        const added = group.map((m) => ({ id: newAreaId(), x: m.x, y: m.y, width: m.width, height: m.height }))
        await saveRedactions(documentId, pageIndex, mergeAreas(existing, added))
        changed.push({ documentId, pageIndex })
      }
      return changed
    })
    if (!done) return
    onChanged(done)
    setResults(null)
    setMessage(
      `${chosen.length}곳을 가림 영역으로 넣었습니다. 페이지 목록에 검은 상자로 보이고, ‘PDF로 내보내기’를 할 때 그 아래 글자가 실제로 지워집니다.`,
    )
  }

  async function clearAll() {
    if (!window.confirm('모든 페이지의 가림 영역을 지울까요?')) return
    const done = await run(async () => {
      const changed: PageRef[] = []
      for (const page of uniquePages) {
        if ((await fetchRedactions(page.documentId, page.pageIndex)).length > 0) {
          await saveRedactions(page.documentId, page.pageIndex, [])
          changed.push(page)
        }
      }
      return changed
    })
    if (!done) return
    onChanged(done)
    setMessage(done.length > 0 ? `${done.length}개 페이지의 가림 영역을 지웠습니다.` : '지울 가림 영역이 없습니다.')
  }

  const toggleKind = (kind: PersonalInfoKind) =>
    setKinds((prev) => {
      const next = new Set(prev)
      if (next.has(kind)) next.delete(kind)
      else next.add(kind)
      return next
    })

  const togglePick = (i: number) =>
    setPicked((prev) => {
      const next = new Set(prev)
      if (next.has(i)) next.delete(i)
      else next.add(i)
      return next
    })

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="find-dialog redact-dialog" role="dialog" aria-modal="true" aria-label="개인정보 찾아 가리기">
        <div className="find-head">
          <b>개인정보 찾아 가리기</b>
          <button type="button" onClick={onClose} aria-label="닫기" disabled={busy}>
            ✕
          </button>
        </div>

        <fieldset className="redact-kinds" disabled={busy}>
          <legend>찾을 항목</legend>
          {PERSONAL_KINDS.map(({ kind, label }) => (
            <label key={kind} className="find-check">
              <input type="checkbox" checked={kinds.has(kind)} onChange={() => toggleKind(kind)} />
              {label}
            </label>
          ))}
        </fieldset>
        <label>
          함께 찾을 글자 (이름, 주소 등 · 선택)
          <input type="text" value={term} onChange={(e) => setTerm(e.target.value)} disabled={busy} maxLength={200} />
        </label>

        <div className="find-actions">
          <button type="button" onClick={() => pages[0] && onDraw(pages[0].key)} disabled={busy || pages.length === 0}>
            직접 그려서 가리기…
          </button>
          <button type="button" onClick={() => void clearAll()} disabled={busy}>
            가림 모두 지우기
          </button>
          <button
            type="button"
            className="primary"
            onClick={() => void search()}
            disabled={busy || (kinds.size === 0 && term.trim() === '')}
          >
            {busy && !results ? '찾는 중…' : '찾기'}
          </button>
        </div>

        {error && <p className="find-message error">{error}</p>}
        {message && <p className="find-message">{message}</p>}

        {results && results.length > 0 && (
          <>
            <div className="redact-results-head">
              <span>{results.length}곳을 찾았습니다. 가릴 항목을 고르세요.</span>
              <button
                type="button"
                className="link-button"
                onClick={() => setPicked(picked.size === results.length ? new Set() : new Set(results.map((_, i) => i)))}
              >
                {picked.size === results.length ? '모두 해제' : '모두 선택'}
              </button>
            </div>
            <ul className="redact-results">
              {results.map((m, i) => {
                const key = pageKey(m)
                return (
                  <li key={`${refKey(m)}-${i}`}>
                    <label className="find-check">
                      <input type="checkbox" checked={picked.has(i)} onChange={() => togglePick(i)} />
                      <span className="redact-kind">{m.label}</span>
                      <span className="redact-text">{m.text}</span>
                    </label>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => key && onDraw(key)}
                      title="그 페이지를 열어 가림 영역을 직접 고칩니다"
                    >
                      {pageNumber(m)}쪽
                    </button>
                  </li>
                )
              })}
            </ul>
            <div className="find-actions">
              <button type="button" className="primary" onClick={() => void redactPicked()} disabled={busy || picked.size === 0}>
                선택한 {picked.size}곳 가리기
              </button>
            </div>
          </>
        )}

        <p className="find-note">
          가림 영역은 내보낼 때 적용됩니다: 그 아래 글자를 PDF에서 지우고, 다시 읽어서 남은 글자가 없는지 확인합니다. 원본 문서는
          바뀌지 않으므로 내보내기 전에는 언제든 고치거나 지울 수 있습니다.
        </p>
      </div>
    </div>
  )
}
