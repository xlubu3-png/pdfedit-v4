import { useEffect, useRef, useState } from 'react'
import type { ClipboardEvent, CSSProperties, KeyboardEvent, MouseEvent, PointerEvent } from 'react'
import { snapPosition } from '../snap'
import type { SnapTargets } from '../snap'
import type { AddedText, PageText, RunState, TextRun } from '../types'

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
}: RunBoxProps) {
  const ref = useRef<HTMLDivElement>(null)
  const selectedAtPress = useRef(false)
  const shownText = state?.text ?? run.text
  const dx0 = state?.dx ?? 0
  const dy0 = state?.dy ?? 0
  const base = { x: run.x + dx0, y: run.y + dy0 }

  useEffect(() => {
    if (ref.current) ref.current.textContent = shownText
  }, [run, shownText])

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

  // boxTop/boxBottom come from the server (the same numbers that size the cover-up rectangle baked
  // into the export), so the on-screen box always matches what gets saved.
  const itemStyle = {
    left: (run.x + dx - data.originX) * scale,
    top: (data.pageHeight - (run.boxTop + dy - data.originY)) * scale,
  }
  const boxStyle: CSSProperties = {
    minWidth: Math.max(run.width * scale, 1),
    minHeight: Math.max((run.boxTop - run.boxBottom) * scale, 1),
    fontSize: Math.max(size * scale * 0.95, 6),
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
          onStopEdit()
        }}
        onKeyDown={editing ? handleKeyDown : undefined}
        onPaste={(e) => pastePlainText(e, false)}
      />
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
