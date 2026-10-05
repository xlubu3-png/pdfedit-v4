import type { FontChoice } from '../types'
import { FontPicker } from './FontPicker'

/** What the bar shows and edits: one text line or one added box, as it looks right now. */
export interface StyleView {
  kind: 'run' | 'added'
  /** "줄 3" or "새 텍스트" */
  label: string
  /** Font the original page uses (only for existing lines). */
  sourceFont: string | null
  /** Family used when the user has not chosen one. */
  autoFamily: string
  /** The user's choice, or null for the automatic one. */
  family: string | null
  size: number
  bold: boolean
  color: string
  /** The line was edited, restyled or moved; "원위치" undoes all of it. */
  canReset: boolean
  /**
   * How an edited line is drawn: "original" keeps the page's own glyphs, "substitute" redraws the whole
   * line in an installed font. Null for a line that was not touched.
   */
  look: 'original' | 'substitute' | null
  /** Typed characters the page's own font lacks: they are drawn in another font, so the line can look mixed. */
  mixedChars: string
}

interface StyleBarProps {
  view: StyleView | null
  fonts: FontChoice[]
  onFamily: (family: string | null) => void
  onSize: (size: number) => void
  onBold: (bold: boolean) => void
  onColor: (color: string) => void
  onReset: () => void
  /** Draws the whole line in one installed font, so no character looks different from its neighbours. */
  onUnify: () => void
  onDelete: () => void
  onDuplicate: () => void
  /** Zoom as a factor (1 = 100%). */
  zoom: number
  onZoomIn: () => void
  onZoomOut: () => void
  onZoomFit: () => void
}

const keepFocus = { onMouseDown: (e: { preventDefault: () => void }) => e.preventDefault() }

/** Font, size, weight and colour of the selected text, shown above the page, plus the zoom. */
export function StyleBar({
  view,
  fonts,
  onFamily,
  onSize,
  onBold,
  onColor,
  onReset,
  onUnify,
  onDelete,
  onDuplicate,
  zoom,
  onZoomIn,
  onZoomOut,
  onZoomFit,
}: StyleBarProps) {
  const disabled = view === null
  const shownFont = view
    ? `${view.family ? (fonts.find((f) => f.name === view.family)?.label ?? view.family) : view.autoFamily}${view.bold ? ' (굵게)' : ''}`
    : ''
  const autoLabel = `${view?.kind === 'added' ? '기본' : '자동'} (${view?.autoFamily ?? '-'})`

  return (
    <div className="te-stylebar" role="toolbar" aria-label="글자 모양">
      <span className="te-style-info">
        {view ? (
          <>
            <b>{view.label}</b>
            {view.sourceFont && <> · 원본 글꼴 {view.sourceFont}</>} · 표시 글꼴 {shownFont} · {view.size.toFixed(1)}pt
            {view.look === 'original' && (
              <span className="te-look ok" title="바꾼 글자도 이 페이지의 원래 글리프로 그려서 모양이 그대로입니다">
                원본 글꼴 유지
              </span>
            )}
            {view.mixedChars && (
              <span
                className="te-look warn"
                title={`'${view.mixedChars}' 글자는 원본 글꼴에 없어 대체 글꼴로 그려지므로 나머지 글자와 모양이 다를 수 있습니다`}
              >
                글꼴 섞임: '{view.mixedChars.length > 6 ? `${view.mixedChars.slice(0, 6)}…` : view.mixedChars}'
              </span>
            )}
            {view.look === 'substitute' && (
              <span className="te-look warn" title="이 줄 전체를 이 PC에 설치된 글꼴로 새로 그립니다 (원본과 조금 다를 수 있음)">
                대체 글꼴로 다시 그림
              </span>
            )}
          </>
        ) : (
          '글자 상자를 클릭해 선택하세요 (더블클릭 또는 Enter로 글자 수정). 선택하면 여기에서 글꼴·크기·굵기·색을 바꿀 수 있습니다'
        )}
      </span>

      <span className="te-style-field">
        글꼴
        <FontPicker fonts={fonts} value={view?.family ?? null} autoLabel={autoLabel} onChange={onFamily} disabled={disabled} />
      </span>

      <span className="te-style-field">
        크기
        <button type="button" disabled={disabled} onClick={() => view && onSize(view.size - 1)} {...keepFocus}>
          −
        </button>
        <input
          type="number"
          min={1}
          max={500}
          step={0.5}
          value={view ? Number(view.size.toFixed(1)) : ''}
          disabled={disabled}
          onChange={(e) => {
            const size = Number(e.target.value)
            if (Number.isFinite(size) && size >= 1 && size <= 500) onSize(size)
          }}
          aria-label="글자 크기 (pt)"
        />
        <button type="button" disabled={disabled} onClick={() => view && onSize(view.size + 1)} {...keepFocus}>
          +
        </button>
        pt
      </span>

      <button
        type="button"
        className={`te-bold${view?.bold ? ' on' : ''}`}
        disabled={disabled}
        aria-pressed={view?.bold ?? false}
        onClick={() => view && onBold(!view.bold)}
        title="굵게 (Ctrl+B)"
        {...keepFocus}
      >
        <b>B</b> 굵게
      </button>

      <label className="te-style-field">
        색
        <input
          type="color"
          value={view?.color ?? '#000000'}
          disabled={disabled}
          onChange={(e) => onColor(e.target.value)}
          aria-label="글자 색"
        />
      </label>

      <button type="button" disabled={disabled} onClick={onDuplicate} title="복제 (Ctrl+D)" {...keepFocus}>
        복제
      </button>
      {view?.kind === 'run' && view.mixedChars && (
        <button
          type="button"
          className="te-unify"
          onClick={onUnify}
          title="이 줄 전체를 한 가지 대체 글꼴로 다시 그려 글자 모양을 통일합니다"
          {...keepFocus}
        >
          줄 전체 글꼴 통일
        </button>
      )}
      {view?.kind === 'run' && (
        <button type="button" disabled={!view.canReset} onClick={onReset} {...keepFocus}>
          원위치
        </button>
      )}
      {view?.kind === 'added' && (
        <button type="button" className="danger" onClick={onDelete} title="삭제 (Delete)" {...keepFocus}>
          삭제
        </button>
      )}

      <span className="te-zoom" role="group" aria-label="화면 크기">
        <button type="button" onClick={onZoomOut} title="축소 (Ctrl+−)">
          −
        </button>
        <span className="te-zoom-value" aria-live="polite">
          {Math.round(zoom * 100)}%
        </span>
        <button type="button" onClick={onZoomIn} title="확대 (Ctrl++)">
          +
        </button>
        <button type="button" onClick={onZoomFit} title="폭에 맞춤 (Ctrl+0)">
          폭 맞춤
        </button>
      </span>
    </div>
  )
}
