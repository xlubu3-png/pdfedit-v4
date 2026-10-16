import { SIMILAR_ENOUGH } from '../fontMix'
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
  /** The letters picked, or the whole line when none are, are underlined. */
  underline: boolean
  /** The line was edited, restyled or moved; "원위치" undoes all of it. */
  canReset: boolean
  /**
   * How an edited line is drawn: "original" keeps the page's own glyphs, "substitute" redraws the whole
   * line in an installed font. Null for a line that was not touched.
   */
  look: 'original' | 'substitute' | null
  /** Typed characters the page's own font lacks: they are drawn in another font, so the line can look mixed. */
  mixedChars: string
  /** The line's font as the PDF names it, when it is not installed on this PC; null when it is (or cannot be told). */
  missingFont: string | null
  /**
   * What became of the original letters of an edited line: "removed" cut out of the page, "covered" still in
   * the file under a cover-up. Null for a line that was not edited, or while the server is still checking.
   */
  original: 'removed' | 'covered' | null
  /** How closely the font chosen for a line whose own font has no name resembles it (0 to 1); null when not judged. */
  similarity: number | null
  /** How many stretches of the line are bold or coloured on their own (partial formatting). */
  partial: number
}

interface StyleBarProps {
  view: StyleView | null
  fonts: FontChoice[]
  onFamily: (family: string | null) => void
  onSize: (size: number) => void
  onBold: (bold: boolean) => void
  /** Underlines the picked letters (or the whole line), or takes the underline off. */
  onUnderline: () => void
  onColor: (color: string) => void
  onReset: () => void
  /** Draws the whole line in one installed font, so no character looks different from its neighbours. */
  onUnify: () => void
  /** Opens the help for fonts this PC lacks. */
  onFontHelp: () => void
  /** Reads the installed fonts again (after the user installed one). */
  onReloadFonts: () => void
  fontsBusy: boolean
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
  onUnderline,
  onColor,
  onReset,
  onUnify,
  onFontHelp,
  onReloadFonts,
  fontsBusy,
  onDelete,
  onDuplicate,
  zoom,
  onZoomIn,
  onZoomOut,
  onZoomFit,
}: StyleBarProps) {
  const disabled = view === null
  const shownFont = view
    ? `${
        view.family
          ? (fonts.find((f) => f.name === view.family)?.label ?? view.family)
          : (fonts.find((f) => f.name === view.autoFamily)?.label ?? view.autoFamily)
      }${view.bold ? ' (굵게)' : ''}`
    : ''
  const autoName = fonts.find((f) => f.name === view?.autoFamily)?.label ?? view?.autoFamily ?? '-'
  const autoLabel = `${view?.kind === 'added' ? '기본' : '자동'} (${autoName})`

  return (
    <div className="te-stylebar" role="toolbar" aria-label="글자 모양">
      <span className="te-style-info">
        {view ? (
          <>
            <b>{view.label}</b>
            {view.sourceFont && <> · 원본 글꼴 {view.sourceFont}</>}
            {view.missingFont && (
              <button
                type="button"
                className="te-look warn te-missing"
                onClick={onFontHelp}
                title="이 원본 글꼴이 PC에 설치되어 있지 않습니다. 누르면 구하는 방법을 보여 줍니다"
                {...keepFocus}
              >
                ⚠ 이 PC에 없음 · 받는 방법
              </button>
            )}{' '}
            · 표시 글꼴 {shownFont} · {view.size.toFixed(1)}pt
            {view.look === 'original' && (
              <span className="te-look ok" title="바꾼 글자도 이 페이지의 원래 글리프로 그려서 모양이 그대로입니다">
                원본 글꼴 유지
              </span>
            )}
            {view.similarity !== null && (
              <span
                className={`te-look ${view.similarity >= SIMILAR_ENOUGH ? 'ok' : 'warn'}`}
                title={
                  view.similarity >= SIMILAR_ENOUGH
                    ? '이 글꼴 정보에는 이름이 없어서, 글자 모양을 설치된 글꼴과 비교해 가장 비슷한 것을 골랐습니다'
                    : '원본과 닮은 설치 글꼴이 없어 모양이 조금 다를 수 있습니다. 글꼴 목록에서 직접 골라 보세요'
                }
              >
                {view.similarity >= SIMILAR_ENOUGH ? '원본과 비슷' : '원본과 다를 수 있음'} {Math.round(view.similarity * 100)}%
              </span>
            )}
            {view.original === 'removed' && (
              <span className="te-look ok" title="수정한 줄의 원래 글자를 PDF 안에서 지웠습니다. 파일에서 검색하거나 복사해도 나오지 않습니다">
                원본 글자 삭제됨
              </span>
            )}
            {view.original === 'covered' && (
              <span
                className="te-look warn"
                title="원래 글자가 PDF 안에 남아 있고 그 위를 덮었습니다. 완전히 없애려면 내보낼 때 ‘이미지로 변환’을 켜세요"
              >
                원본 글자 가려짐
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
            {view.partial > 0 && (
              <span
                className="te-look ok"
                title="글자를 드래그해 고른 뒤 굵게·색을 누르면 고른 글자에만 적용됩니다. 고르지 않고 누르면 줄 전체에 적용되고 부분 서식은 사라집니다"
              >
                부분 서식 {view.partial}곳
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
        <button
          type="button"
          className="te-reload-fonts"
          onClick={onReloadFonts}
          disabled={fontsBusy}
          title="글꼴을 새로 설치했다면 눌러서 설치된 글꼴을 다시 읽습니다 (앱을 다시 켤 필요 없음)"
          aria-label="글꼴 다시 읽기"
          {...keepFocus}
        >
          {fontsBusy ? '…' : '⟳'}
        </button>
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
        title="굵게 (Ctrl+B) — 글자를 드래그해 고르면 고른 글자만, 고르지 않으면 줄 전체"
        {...keepFocus}
      >
        <b>B</b> 굵게
      </button>

      <button
        type="button"
        className={`te-bold${view?.underline ? ' on' : ''}`}
        disabled={disabled || view?.kind === 'added'}
        aria-pressed={view?.underline ?? false}
        onClick={onUnderline}
        title="밑줄 (Ctrl+U) — 글자를 드래그해 고르면 고른 글자만, 고르지 않으면 줄 전체"
        {...keepFocus}
      >
        <u>U</u> 밑줄
      </button>

      <label className="te-style-field">
        색
        <input
          type="color"
          value={view?.color ?? '#000000'}
          disabled={disabled}
          onChange={(e) => onColor(e.target.value)}
          title="글자 색 — 글자를 드래그해 고르면 고른 글자만, 고르지 않으면 줄 전체"
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
