import { useEffect, useMemo, useRef, useState } from 'react'
import type { ClipboardEvent, CSSProperties, KeyboardEvent, MouseEvent, PointerEvent } from 'react'
import { estimatedWidth, overflows, overlayFit } from '../overflow'
import { snapPosition } from '../snap'
import type { SnapTargets } from '../snap'
import { cleanSpans } from '../spans'
import type { AddedText, PageText, RunState, Span, TextRun } from '../types'

/** Fractions of a line height: where a text box's top edge sits above the baseline of its first line. */
const ADDED_ASCENT = 1.0
const LINE_SPACING = 1.2
/** A box must be dragged this many pixels before it counts as a move rather than a click. */
const DRAG_THRESHOLD = 3
/** How close (in pixels) a box has to come to another line to be pulled onto it. */
const SNAP_PIXELS = 6

const round = (value: number) => Math.round(value * 10) / 10

/** Dark or light? Decides what the box behind focused text looks like so the text stays readable. */
function isLight(hex: string): boolean {
  const n = Number.parseInt(hex.slice(1), 16)
  if (Number.isNaN(n)) return false
  const luminance = (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255
  return luminance > 0.75
}

function boxVars(color: string): CSSProperties {
  return { '--box-color': color, '--box-bg': isLight(color) ? '#555' : '#fff' } as CSSProperties
}

/** Where the dragged box was pulled onto another line, in PDF points, for the guide lines. */
export interface Guides {
  x: number | null
  y: number | null
}

/**
 * Drags by pointer: reports how far the pointer moved, in PDF points (y up), once it has moved a few
 * pixels, and the final distance when it is released. A press that never moves is left to the click.
 */
function useDrag(scale: number, onStart: () => void, onCommit: (dx: number, dy: number) => void) {
  const [delta, setDelta] = useState<{ x: number; y: number } | null>(null)
  const origin = useRef<{ x: number; y: number } | null>(null)
  const moved = useRef(false)
  const justDragged = useRef(false)

  const distance = (e: PointerEvent<HTMLElement>) => ({
    x: (e.clientX - (origin.current?.x ?? 0)) / scale,
    y: -(e.clientY - (origin.current?.y ?? 0)) / scale,
  })

  const handlers = {
    onMouseDown: (e: MouseEvent) => e.preventDefault(), // keep the focus where it is
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      if (e.button !== 0) return
      e.preventDefault()
      e.currentTarget.setPointerCapture(e.pointerId)
      origin.current = { x: e.clientX, y: e.clientY }
      moved.current = false
      justDragged.current = false
      onStart()
    },
    onPointerMove: (e: PointerEvent<HTMLElement>) => {
      if (!origin.current) return
      if (!moved.current && Math.hypot(e.clientX - origin.current.x, e.clientY - origin.current.y) < DRAG_THRESHOLD) return
      moved.current = true
      setDelta(distance(e))
    },
    onPointerUp: (e: PointerEvent<HTMLElement>) => {
      if (!origin.current) return
      const final = distance(e)
      const wasMoved = moved.current
      origin.current = null
      moved.current = false
      justDragged.current = wasMoved
      setDelta(null)
      if (wasMoved) onCommit(final.x, final.y)
    },
    onPointerCancel: () => {
      origin.current = null
      moved.current = false
      setDelta(null)
    },
  }

  /** True once after a drag, so the click that ends it is not mistaken for "click to edit". */
  const consumeClick = () => {
    const was = justDragged.current
    justDragged.current = false
    return was
  }
  return { delta, handlers, consumeClick }
}

/** Alt+arrows nudge by 1 point (Shift: 10) even while typing; plain arrows keep moving the text cursor. */
function nudgeOf(e: KeyboardEvent<HTMLDivElement>): { x: number; y: number } | null {
  if (!e.altKey) return null
  const step = e.shiftKey ? 10 : 1
  switch (e.key) {
    case 'ArrowLeft':
      return { x: -step, y: 0 }
    case 'ArrowRight':
      return { x: step, y: 0 }
    case 'ArrowUp':
      return { x: 0, y: step }
    case 'ArrowDown':
      return { x: 0, y: -step }
    default:
      return null
  }
}

function pastePlainText(e: ClipboardEvent<HTMLDivElement>, keepLineBreaks: boolean) {
  e.preventDefault()
  const raw = e.clipboardData.getData('text/plain')
  document.execCommand('insertText', false, keepLineBreaks ? raw.replace(/\r\n?/g, '\n') : raw.replace(/\r?\n/g, ' '))
}

/** Puts the text cursor after the last character: where the user expects to carry on typing. */
function focusAtEnd(el: HTMLElement | null) {
  if (!el) return
  el.focus()
  const selection = window.getSelection()
  if (!selection) return
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}

/** What dragging a box from `base` by `drag` points comes to once it is pulled onto nearby lines. */
function snapped(
  base: { x: number; y: number },
  drag: { x: number; y: number } | null,
  targets: SnapTargets,
  scale: number,
) {
  if (!drag) return { dx: 0, dy: 0, guides: null as Guides | null }
  const s = snapPosition(base.x + drag.x, base.y + drag.y, targets, SNAP_PIXELS / scale)
  return { dx: s.x - base.x, dy: s.y - base.y, guides: { x: s.guideX, y: s.guideY } }
}

interface RunBoxProps {
  run: TextRun
  /** What the user has done to this run so far, if anything. */
  state: RunState | undefined
  scale: number
  data: PageText
  selected: boolean
  /** The box takes keyboard input (otherwise a click only selects it, and a drag moves it). */
  editing: boolean
  /** Lines of the other boxes, for snapping while dragging. */
  targets: SnapTargets
  onSelect: () => void
  onStartEdit: () => void
  onStopEdit: () => void
  onCommitText: (run: TextRun, text: string) => void
  onMoveTo: (run: TextRun, dx: number, dy: number) => void
  onGuides: (guides: Guides | null) => void
  /** The letters picked inside the box while typing (character offsets), or null once the cursor is just placed. */
  onSelection: (run: TextRun, range: { start: number; end: number } | null) => void
}

/** Where the picked text starts and ends inside `el`, as character offsets; null when nothing there is picked. */
function selectionOffsets(el: HTMLElement): { start: number; end: number } | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) return null
  const range = selection.getRangeAt(0)
  if (!el.contains(range.startContainer) || !el.contains(range.endContainer)) return null
  const before = range.cloneRange()
  before.selectNodeContents(el)
  before.setEnd(range.startContainer, range.startOffset)
  const start = before.toString().length
  return { start, end: start + range.toString().length }
}

/** Picks characters `start` to `end` of `el` again (after its content was redrawn). */
function restoreOffsets(el: HTMLElement, start: number, end: number) {
  const selection = window.getSelection()
  if (!selection) return
  const range = document.createRange()
  let seen = 0
  let placedStart = false
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const length = node.textContent?.length ?? 0
    if (!placedStart && start <= seen + length) {
      range.setStart(node, start - seen)
      placedStart = true
    }
    if (placedStart && end <= seen + length) {
      range.setEnd(node, end - seen)
      selection.removeAllRanges()
      selection.addRange(range)
      return
    }
    seen += length
  }
}

/** Fills a box with its text, the stretches that have a look of their own in their own weight and colour. */
function paintBox(el: HTMLElement, text: string, spans: Span[]) {
  el.textContent = ''
  let at = 0
  for (const span of cleanSpans(spans, text.length)) {
    if (span.start > at) el.append(text.slice(at, span.start))
    const part = document.createElement('span')
    part.textContent = text.slice(span.start, span.end)
    if (span.bold !== null) part.style.fontWeight = span.bold ? 'bold' : 'normal'
    if (span.color !== null) part.style.color = span.color
    el.append(part)
    at = span.end
  }
  if (at < text.length) el.append(text.slice(at))
}

/**
 * One box laid over a text run of the rendered page. Unedited boxes are invisible so the real PDF
 * rendering shows through untouched; the text lives in the DOM (not React state) so re-renders never
 * clobber what the user is typing. A click selects, a drag (by the box or its ✥ handle) moves, and a
 * second click, double-click or Enter starts typing.
 */
export function RunBox({
  run,
  state,
  scale,
  data,
  selected,
  editing,
  targets,
  onSelect,
  onStartEdit,
  onStopEdit,
  onCommitText,
  onMoveTo,
  onGuides,
  onSelection,
}: RunBoxProps) {
  const ref = useRef<HTMLDivElement>(null)
  const selectedAtPress = useRef(false)
  /** What is being typed right now, before it is committed; null when nothing is being typed. */
  const [typed, setTyped] = useState<string | null>(null)
  const shownText = state?.text ?? run.text
  const spans = state?.spans
  const spansKey = JSON.stringify(spans ?? [])
  const dx0 = state?.dx ?? 0
  const dy0 = state?.dy ?? 0
  const base = { x: run.x + dx0, y: run.y + dy0 }

  useEffect(() => {
    const el = ref.current
    if (!el) return
    // Redrawing the content while the user has letters picked would drop the pick: take it up again after.
    const picked = document.activeElement === el ? selectionOffsets(el) : null
    paintBox(el, shownText, JSON.parse(spansKey) as Span[])
    if (picked) restoreOffsets(el, picked.start, picked.end)
  }, [run, shownText, spansKey])

  useEffect(() => {
    if (editing) focusAtEnd(ref.current)
  }, [editing])

  // While typing, tell the editor which letters are picked, so bold and colour can go to just those.
  useEffect(() => {
    if (!editing) return
    const report = () => {
      const el = ref.current
      if (!el) return
      const range = selectionOffsets(el)
      if (range === null) return // the pick is somewhere else (the colour dialog took the focus): keep what we had
      onSelection(run, range.end > range.start ? range : null)
    }
    document.addEventListener('selectionchange', report)
    return () => document.removeEventListener('selectionchange', report)
  }, [editing, run, onSelection])

  const drag = useDrag(
    scale,
    () => {
      selectedAtPress.current = selected
      onSelect()
    },
    (ddx, ddy) => {
      const s = snapped(base, { x: ddx, y: ddy }, targets, scale)
      onMoveTo(run, round(dx0 + s.dx), round(dy0 + s.dy))
    },
  )
  const live = snapped(base, drag.delta, targets, scale)
  const dx = dx0 + live.dx
  const dy = dy0 + live.dy
  const guideX = live.guides?.x ?? null
  const guideY = live.guides?.y ?? null
  const dragging = drag.delta !== null
  useEffect(() => {
    onGuides(dragging ? { x: guideX, y: guideY } : null)
  }, [dragging, guideX, guideY, onGuides])

  const size = state?.fontSize ?? run.fontSize
  const color = state?.color ?? run.color
  // The text shown while typing is set in the browser's version of the font: shrunk the way the page's own
  // narrower letters are, so it does not look bigger than the finished line (a font the user chose is as it is).
  const ownFit = useMemo(() => overlayFit(run), [run])
  const standInFit = state?.fontFamily ? 1 : ownFit
  // The box is at least as wide as the finished line will be, so its frame covers all of the new text.
  const textNow = typed ?? shownText
  const grownWidth =
    textNow === run.text
      ? run.width
      : estimatedWidth(run, textNow, state?.fontFamily ?? run.fontFamily, size, state?.bold ?? run.bold)
  const tooLong = overflows(
    run,
    data,
    typed ?? shownText,
    state?.fontFamily ?? run.fontFamily,
    size,
    state?.bold ?? run.bold,
    dx0,
  )

  // boxTop/boxBottom come from the server (the same numbers that size the cover-up rectangle baked
  // into the export), so the on-screen box always matches what gets saved.
  const itemStyle = {
    left: (run.x + dx - data.originX) * scale,
    top: (data.pageHeight - (run.boxTop + dy - data.originY)) * scale,
  }
  const boxStyle: CSSProperties = {
    minWidth: Math.max(Math.max(run.width, grownWidth) * scale, 1),
    minHeight: Math.max((run.boxTop - run.boxBottom) * scale, 1),
    fontSize: Math.max(size * scale * 0.95 * standInFit, 6),
    fontFamily: `'${state?.fontFamily ?? run.fontFamily}'`,
    fontWeight: (state?.bold ?? run.bold) ? 'bold' : undefined,
    ...boxVars(color),
  }

  function handleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const nudge = nudgeOf(e)
    if (nudge) {
      e.preventDefault()
      onMoveTo(run, round(dx0 + nudge.x), round(dy0 + nudge.y))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      e.currentTarget.blur()
    }
  }

  return (
    <div className={`te-item${selected ? ' selected' : ''}${dragging ? ' dragging' : ''}`} style={itemStyle}>
      <div className="te-handle" title="끌어서 이동 (방향키: 1pt, Shift+방향키: 10pt)" {...drag.handlers}>
        ✥
      </div>
      <div
        ref={ref}
        className={`te-box${state ? ' edited' : ''}${editing ? ' typing' : ''}`}
        style={boxStyle}
        data-index={run.index}
        contentEditable={editing}
        suppressContentEditableWarning
        spellCheck={false}
        {...(editing ? {} : drag.handlers)}
        onClick={() => {
          if (!editing && !drag.consumeClick() && selectedAtPress.current) onStartEdit()
        }}
        // Always keep what was typed when the focus leaves: a click on the page turns "editing" off
        // *before* the browser moves the focus, so the blur arrives when `editing` is already false.
        onBlur={(e) => {
          onCommitText(run, e.currentTarget.textContent ?? '')
          setTyped(null)
          onStopEdit()
        }}
        onInput={(e) => setTyped(e.currentTarget.textContent ?? '')}
        onKeyDown={editing ? handleKeyDown : undefined}
        onPaste={(e) => pastePlainText(e, false)}
      />
      {tooLong && (
        <div className="te-overflow" role="status" title="글이 길어져 오른쪽 글자나 페이지 끝과 겹칠 수 있습니다. 글자 크기를 줄이거나 글을 줄여 보세요">
          ⚠ 글이 길어져 겹칠 수 있음
        </div>
      )}
    </div>
  )
}

interface AddedBoxProps {
  box: AddedText
  scale: number
  data: PageText
  selected: boolean
  editing: boolean
  targets: SnapTargets
  onSelect: () => void
  onStartEdit: () => void
  onStopEdit: () => void
  /** `next` is where the focus went, so a click on the style bar is not mistaken for leaving the box. */
  onCommitText: (box: AddedText, text: string, next: Element | null) => void
  onMoveTo: (box: AddedText, x: number, y: number) => void
  onRemove: (box: AddedText) => void
  onGuides: (guides: Guides | null) => void
}

/** A text box the user added: see-through over the re-rendered page, visible while typing or until the new preview arrives. */
export function AddedBox({
  box,
  scale,
  data,
  selected,
  editing,
  targets,
  onSelect,
  onStartEdit,
  onStopEdit,
  onCommitText,
  onMoveTo,
  onRemove,
  onGuides,
}: AddedBoxProps) {
  const ref = useRef<HTMLDivElement>(null)
  const selectedAtPress = useRef(false)
  const base = { x: box.x, y: box.y }

  useEffect(() => {
    if (ref.current) ref.current.textContent = box.text
  }, [box.id, box.text])

  useEffect(() => {
    if (editing) focusAtEnd(ref.current)
  }, [editing])

  const drag = useDrag(
    scale,
    () => {
      selectedAtPress.current = selected
      onSelect()
    },
    (ddx, ddy) => {
      const s = snapped(base, { x: ddx, y: ddy }, targets, scale)
      onMoveTo(box, round(box.x + s.dx), round(box.y + s.dy))
    },
  )
  const live = snapped(base, drag.delta, targets, scale)
  const x = box.x + live.dx
  const y = box.y + live.dy
  const guideX = live.guides?.x ?? null
  const guideY = live.guides?.y ?? null
  const dragging = drag.delta !== null
  useEffect(() => {
    onGuides(dragging ? { x: guideX, y: guideY } : null)
  }, [dragging, guideX, guideY, onGuides])

  const itemStyle = {
    left: (x - data.originX) * scale,
    top: (data.pageHeight - (y + box.fontSize * ADDED_ASCENT - data.originY)) * scale,
  }
  const boxStyle: CSSProperties = {
    minWidth: Math.max(box.fontSize * scale * 4, 40),
    minHeight: box.fontSize * scale * LINE_SPACING,
    fontSize: box.fontSize * scale,
    lineHeight: LINE_SPACING,
    padding: 0,
    fontFamily: box.fontFamily ? `'${box.fontFamily}'` : "'맑은 고딕'",
    fontWeight: box.bold ? 'bold' : undefined,
    ...boxVars(box.color ?? '#000000'),
  }

  function handleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const nudge = nudgeOf(e)
    if (nudge) {
      e.preventDefault()
      onMoveTo(box, round(box.x + nudge.x), round(box.y + nudge.y))
    }
  }

  return (
    <div className={`te-item added${selected ? ' selected' : ''}${dragging ? ' dragging' : ''}`} style={itemStyle}>
      <div className="te-handle" title="끌어서 이동 (방향키: 1pt, Shift+방향키: 10pt)" {...drag.handlers}>
        ✥
      </div>
      <button
        type="button"
        className="te-remove"
        title="이 텍스트 상자 삭제 (Delete)"
        aria-label="텍스트 상자 삭제"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => onRemove(box)}
      >
        ✕
      </button>
      <div
        ref={ref}
        className={`te-box edited${editing ? ' typing' : ''}`}
        style={boxStyle}
        data-added={box.id}
        contentEditable={editing ? 'plaintext-only' : false}
        suppressContentEditableWarning
        spellCheck={false}
        {...(editing ? {} : drag.handlers)}
        onClick={() => {
          if (!editing && !drag.consumeClick() && selectedAtPress.current) onStartEdit()
        }}
        // Always keep what was typed when the focus leaves (see RunBox: `editing` may already be false).
        onBlur={(e) => {
          onCommitText(box, e.currentTarget.textContent ?? '', e.relatedTarget as Element | null)
          onStopEdit()
        }}
        onKeyDown={editing ? handleKeyDown : undefined}
        onPaste={(e) => pastePlainText(e, true)}
      />
    </div>
  )
}
