import { useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { findText, replaceText } from '../api'
import type { FindMatch, PageItem, ReplaceResult } from '../types'

interface FindReplaceDialogProps {
  /** The pages searched: all the pages in the list, in order. */
  pages: PageItem[]
  initialQuery?: string
  onClose: () => void
  /** Open the editor at a page the text was found on. */
  onOpenPage: (key: string) => void
  /** Called after a replace, so previews and thumbnails of the changed pages can be refreshed. */
  onReplaced: (result: ReplaceResult) => void
}

/** The text with every occurrence of `query` marked. */
function highlight(text: string, query: string, matchCase: boolean): ReactNode {
  if (!query) return text
  const haystack = matchCase ? text : text.toLowerCase()
  const needle = matchCase ? query : query.toLowerCase()
  const parts: ReactNode[] = []
  let from = 0
  for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, from)) {
    if (at > from) parts.push(text.slice(from, at))
    parts.push(<mark key={at}>{text.slice(at, at + needle.length)}</mark>)
    from = at + needle.length
  }
  parts.push(text.slice(from))
  return parts
}

/** Find and replace over all the pages: one text line at a time, as the editor sees the text. */
export function FindReplaceDialog({ pages, initialQuery = '', onClose, onOpenPage, onReplaced }: FindReplaceDialogProps) {
  const [query, setQuery] = useState(initialQuery)
  const [replacement, setReplacement] = useState('')
  const [matchCase, setMatchCase] = useState(false)
  const [results, setResults] = useState<FindMatch[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  async function run<T>(work: () => Promise<T>): Promise<T | undefined> {
    setBusy(true)
    setMessage('')
    try {
      return await work()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : '실패했습니다')
      return undefined
    } finally {
      setBusy(false)
    }
  }

  async function find() {
    if (!query) return
    const found = await run(() => findText(pages, query, matchCase))
    if (found) {
      setResults(found)
      setMessage(found.length === 0 ? '찾는 글자가 없습니다.' : `${found.length}개 줄에서 찾았습니다.`)
    }
  }

  async function replaceAll() {
    if (!query) return
    const found = await run(() => findText(pages, query, matchCase))
    if (!found) return
    if (found.length === 0) {
      setResults([])
      setMessage('찾는 글자가 없습니다.')
      return
    }
    const ok = window.confirm(
      `${found.length}개 줄의 '${query}'을(를) '${replacement}'(으)로 모두 바꿀까요?\n` +
        '바꾼 뒤에는 각 페이지의 `이 페이지 되돌리기`로 되돌릴 수 있습니다.',
    )
    if (!ok) return
    const result = await run(() => replaceText(pages, query, replacement, matchCase))
    if (result) {
      setResults(null)
      setMessage(
        `${result.runsChanged}개 줄에서 ${result.replacements}곳을 바꿨습니다.` +
          (result.skipped ? ` (${result.skipped})` : ''),
      )
      onReplaced(result)
    }
  }

  function handleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'Escape') {
      e.stopPropagation()
      onClose()
    }
  }

  const label = (m: FindMatch) => {
    const index = pages.findIndex((p) => p.documentId === m.documentId && p.pageIndex === m.pageIndex)
    return index >= 0 ? `${index + 1}쪽 · ${pages[index].sourceFileName}` : `${m.pageIndex + 1}쪽`
  }

  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="find-dialog" role="dialog" aria-modal="true" aria-label="찾아 바꾸기" onKeyDown={handleKeyDown}>
        <div className="find-head">
          <b>찾아 바꾸기</b>
          <button type="button" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>
        <label>
          찾을 글자
          <input
            type="text"
            value={query}
            ref={(el) => el?.focus()}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && void find()}
          />
        </label>
        <label>
          바꿀 글자
          <input
            type="text"
            value={replacement}
            onChange={(e) => setReplacement(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && void replaceAll()}
          />
        </label>
        <label className="find-check">
          <input type="checkbox" checked={matchCase} onChange={(e) => setMatchCase(e.target.checked)} />
          대소문자 구분
        </label>
        <div className="find-actions">
          <button type="button" onClick={find} disabled={busy || !query}>
            찾기
          </button>
          <button type="button" className="primary" onClick={replaceAll} disabled={busy || !query}>
            모두 바꾸기
          </button>
        </div>
        <p className="find-note">한 줄 안의 글자만 찾습니다 (줄이 바뀌는 곳에 걸친 글자는 찾지 못합니다).</p>
        {message && (
          <p className="find-message" aria-live="polite">
            {message}
          </p>
        )}
        {results && results.length > 0 && (
          <ul className="find-results">
            {results.map((m) => {
              const page = pages.find((p) => p.documentId === m.documentId && p.pageIndex === m.pageIndex)
              return (
                <li key={`${m.documentId}-${m.pageIndex}-${m.runIndex}`}>
                  <button
                    type="button"
                    disabled={!page}
                    onClick={() => {
                      if (page) {
                        onOpenPage(page.key)
                        onClose()
                      }
                    }}
                  >
                    <span className="find-where">{label(m)}</span>
                    <span className="find-text">{highlight(m.text, query, matchCase)}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </div>
  )
}
