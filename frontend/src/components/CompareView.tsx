import { useEffect, useMemo, useState } from 'react'
import { compareDocuments, compareLook, originalUrl } from '../api'
import { areaStyle, changedPages, documentsIn, pairCount } from '../compare'
import type { CompareResult, PageItem, PageSize, TextChange, VisualResult } from '../types'

interface CompareViewProps {
  pages: PageItem[]
  onClose: () => void
}

/** Room left beside the two pages for the list of changes, in pixels. */
const SIDEBAR = 280

/**
 * Two uploaded documents side by side: text taken out of the first is marked red on it, text put into the
 * second is marked green on that one. On request the pages are also compared by look, for what is not text.
 */
export function CompareView({ pages, onClose }: CompareViewProps) {
  const documents = useMemo(() => documentsIn(pages), [pages])
  const [a, setA] = useState(documents[0]?.documentId ?? '')
  const [b, setB] = useState(documents[1]?.documentId ?? '')
  const [result, setResult] = useState<CompareResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [index, setIndex] = useState(0)
  const [look, setLook] = useState(false)
  const [visual, setVisual] = useState<Map<number, VisualResult>>(new Map())

  async function run() {
    setBusy(true)
    setError(null)
    try {
      const compared = await compareDocuments(a, b)
      setResult(compared)
      setVisual(new Map())
      setIndex(changedPages(compared)[0]?.index ?? 0)
    } catch (e) {
      setError(e instanceof Error ? e.message : '문서를 비교하지 못했습니다')
    } finally {
      setBusy(false)
    }
  }

  // The look of a pair of pages is compared when it is first shown with "모양 차이" on.
  useEffect(() => {
    if (!result || !look || visual.has(index)) return
    if (index >= result.pagesA.length || index >= result.pagesB.length) return
    let cancelled = false
    compareLook(result.a, index, result.b, index)
      .then((found) => !cancelled && setVisual((prev) => new Map(prev).set(index, found)))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '모양을 비교하지 못했습니다'))
    return () => {
      cancelled = true
    }
  }, [result, look, index, visual])

  useEffect(() => {
    const listener = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement) return
      if (e.key === 'Escape') onClose()
      else if (result && e.key === 'ArrowRight') setIndex((i) => Math.min(i + 1, pairCount(result) - 1))
      else if (result && e.key === 'ArrowLeft') setIndex((i) => Math.max(i - 1, 0))
    }
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [result, onClose])

  const changed = result ? changedPages(result) : []
  const here = changed.find((c) => c.index === index)
  const nextChange = changed.find((c) => c.index > index)
  const previousChange = [...changed].reverse().find((c) => c.index < index)
  const columnWidth = Math.max(240, Math.min(620, (window.innerWidth - SIDEBAR - 72) / 2))

  return (
    <div className="te-overlay cmp-overlay" role="dialog" aria-modal="true" aria-label="문서 비교">
      <header className="te-header">
        <button type="button" onClick={onClose}>
          ← 페이지 목록
        </button>
        <span className="te-title">문서 비교</span>
        <span className="te-spacer" />
        {result && (
          <>
            <span className="cmp-summary" aria-live="polite">
              {changed.length === 0
                ? '글자 차이 없음'
                : `바뀐 쪽 ${changed.length} · 삭제 ${result.removedWords}어절 · 추가 ${result.addedWords}어절`}
            </span>
            <label className="te-flatten" title="그림·선·도장처럼 글자가 아닌 차이도 주황색 테두리로 표시합니다">
              <input type="checkbox" checked={look} onChange={(e) => setLook(e.target.checked)} />
              모양 차이
            </label>
            <span className="te-group">
              <button
                type="button"
                onClick={() => previousChange && setIndex(previousChange.index)}
                disabled={!previousChange}
                title="이전 바뀐 쪽"
              >
                ⇤
              </button>
              <button type="button" onClick={() => setIndex((i) => i - 1)} disabled={index <= 0} title="이전 쪽 (←)">
                ◀
              </button>
              <span className="te-sub">
                {index + 1} / {pairCount(result)}쪽
              </span>
              <button
                type="button"
                onClick={() => setIndex((i) => i + 1)}
                disabled={index >= pairCount(result) - 1}
                title="다음 쪽 (→)"
              >
                ▶
              </button>
              <button type="button" onClick={() => nextChange && setIndex(nextChange.index)} disabled={!nextChange} title="다음 바뀐 쪽">
                ⇥
              </button>
            </span>
            <button type="button" onClick={() => setResult(null)}>
              다른 문서 고르기
            </button>
          </>
        )}
      </header>

      {error && (
        <div className="error-banner te-error">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {!result && (
        <main className="te-main">
          <div className="cmp-pick">
            {documents.length < 2 ? (
              <p>비교하려면 PDF를 두 개 이상 올리세요. 같은 문서의 이전 판과 새 판을 올리면 바뀐 글자를 표시합니다.</p>
            ) : (
              <>
                <label>
                  이전 판 (빨간색: 지워진 글자)
                  <select value={a} onChange={(e) => setA(e.target.value)} disabled={busy}>
                    {documents.map((d) => (
                      <option key={d.documentId} value={d.documentId}>
                        {d.fileName}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  className="cmp-swap"
                  onClick={() => {
                    setA(b)
                    setB(a)
                  }}
                  disabled={busy}
                  title="두 문서를 서로 바꿉니다"
                >
                  ⇅
                </button>
                <label>
                  새 판 (초록색: 추가된 글자)
                  <select value={b} onChange={(e) => setB(e.target.value)} disabled={busy}>
                    {documents.map((d) => (
                      <option key={d.documentId} value={d.documentId}>
                        {d.fileName}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="button" className="primary" onClick={() => void run()} disabled={busy || !a || !b || a === b}>
                  {busy ? '비교하는 중…' : '비교하기'}
                </button>
                {a === b && <p className="find-message error">서로 다른 두 문서를 고르세요.</p>}
                <p className="find-note">
                  올린 원본 파일끼리 비교합니다(이 앱에서 고친 내용은 들어가지 않습니다). 문서 전체를 이어서 비교하므로, 글자가 다음 쪽으로
                  밀리기만 한 것은 바뀐 것으로 보지 않습니다.
                </p>
              </>
            )}
          </div>
        </main>
      )}

      {result && (
        <div className="cmp-body">
          <aside className="cmp-list" aria-label="바뀐 곳">
            {changed.length === 0 && <p className="find-note">두 문서의 글자가 같습니다. ‘모양 차이’를 켜면 그림·선의 차이를 볼 수 있습니다.</p>}
            {changed.map((c) => (
              <button
                type="button"
                key={c.index}
                className={`cmp-entry${c.index === index ? ' current' : ''}`}
                onClick={() => setIndex(c.index)}
              >
                <b>{c.index + 1}쪽</b>
                {c.removed.slice(0, 3).map((r, i) => (
                  <span key={`r${i}`} className="cmp-removed">
                    − {r.text}
                  </span>
                ))}
                {c.added.slice(0, 3).map((r, i) => (
                  <span key={`a${i}`} className="cmp-added">
                    + {r.text}
                  </span>
                ))}
                {c.removed.length + c.added.length > 6 && <span className="cmp-more">…</span>}
              </button>
            ))}
          </aside>
          <main className="cmp-pages">
            <ComparedPage
              title={`이전 판 · ${result.fileNameA}`}
              documentId={result.a}
              pageIndex={index}
              size={result.pagesA[index]}
              width={columnWidth}
              changes={here?.removed ?? []}
              kind="removed"
              look={look ? visual.get(index) : undefined}
            />
            <ComparedPage
              title={`새 판 · ${result.fileNameB}`}
              documentId={result.b}
              pageIndex={index}
              size={result.pagesB[index]}
              width={columnWidth}
              changes={here?.added ?? []}
              kind="added"
              look={look ? visual.get(index) : undefined}
            />
          </main>
        </div>
      )}
    </div>
  )
}

interface ComparedPageProps {
  title: string
  documentId: string
  pageIndex: number
  /** Missing when the document has no such page. */
  size: PageSize | undefined
  width: number
  changes: TextChange[]
  kind: 'removed' | 'added'
  look: VisualResult | undefined
}

function ComparedPage({ title, documentId, pageIndex, size, width, changes, kind, look }: ComparedPageProps) {
  const scale = size ? width / size.width : 0
  return (
    <section className="cmp-page" style={{ width }}>
      <h3>{title}</h3>
      {!size ? (
        <p className="cmp-missing">이 문서에는 {pageIndex + 1}쪽이 없습니다.</p>
      ) : (
        <div className="te-canvas">
          <img
            src={originalUrl(documentId, pageIndex, width * Math.min(2, window.devicePixelRatio || 1))}
            alt={`${title} ${pageIndex + 1}쪽`}
            style={{ width }}
          />
          {changes.map((c, i) => (
            <div key={i} className={`cmp-mark ${kind}`} style={areaStyle(c.area, size, scale)} title={c.text} data-testid={`cmp-${kind}`} />
          ))}
          {look?.areas.map((area, i) => (
            <div key={`v${i}`} className="cmp-mark look" style={areaStyle(area, size, scale)} />
          ))}
          {look && !look.comparable && <p className="cmp-look-note">쪽 크기가 달라 모양은 비교하지 않았습니다.</p>}
        </div>
      )}
    </section>
  )
}
