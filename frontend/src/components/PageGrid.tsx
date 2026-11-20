import { useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import { canMoveBy, dropAt, moveBy, moveTo, moveToNumber } from '../pageOrder'
import { BLOCKED_NOTE, useRightClick } from '../rightClick'
import type { PageItem } from '../types'
import { ContextMenu } from './ContextMenu'
import type { MenuEntry } from './ContextMenu'
import { PageThumbnail } from './PageThumbnail'

interface PageGridProps {
  pages: PageItem[]
  selectedKeys: Set<string>
  busy: boolean
  /** The pages in a new order (a drag, or a move from the menu): one step Ctrl+Z takes back. */
  onReorder: (pages: PageItem[]) => void
  onRotate: (key: string, delta: 90 | -90) => void
  onDelete: (key: string) => void
  onToggleSelect: (key: string) => void
  /** The picked pages, all at once: a box dragged over the list picks the pages it touches. */
  onSelectKeys: (keys: Set<string>) => void
  onInsertBlankAfter: (key: string) => void
  onExtract: (key: string) => void
  onDuplicate: (key: string) => void
  onEditText: (key: string) => void
  /** Draws redaction areas on the page by hand. */
  onRedact: (key: string) => void
  /** Recognizes the page's text (a scan). */
  onOcr: (key: string) => void
  /** Sets the page (or the picked pages, when it is one of them) straight, if scanned askew. */
  onStraighten: (key: string) => void
  onPrint: (key: string) => void
  /** Saves the page as a picture (사진으로 내보내기). */
  onSaveImage: (key: string) => void
  /** Saves the page as an Excel file, its tables as on the page. */
  onSaveExcel: (key: string) => void
}

/** How far (pixels) the pointer moves with the button down before a press on a card becomes a drag. */
const DRAG_START = 6
/** How close (pixels) to the top or bottom of the window a drag scrolls the list. */
const SCROLL_EDGE = 70

/**
 * A box being dragged out from an empty part of the list: where it started (in the page, so it stays put when the
 * list scrolls), the pointer, and the pages picked before it (kept when Ctrl or Shift was held).
 */
interface Band {
  pointerId: number
  startX: number
  startY: number
  x: number
  y: number
  base: Set<string>
  /** The pages picked when the box was started: what Esc brings back. */
  before: Set<string>
  shown: boolean
  /** The card it was started on (with Ctrl or Shift held): let go without a drag, it is a click on that card. */
  fromKey?: string
  ctrl: boolean
  shift: boolean
}

/** The pages whose cards the box (window coordinates) touches. */
function keysInBox(grid: HTMLElement | null, left: number, top: number, right: number, bottom: number): Set<string> {
  const keys = new Set<string>()
  for (const card of Array.from(grid?.querySelectorAll<HTMLElement>('.page-card[data-key]') ?? [])) {
    const r = card.getBoundingClientRect()
    if (r.right >= left && r.left <= right && r.bottom >= top && r.top <= bottom) keys.add(card.dataset.key!)
  }
  return keys
}

/** The pages the box touches now, picked (with the pages picked before it when it was started with Ctrl/Shift). */
function pickInBand(grid: HTMLElement | null, band: Band, select: (keys: Set<string>) => void) {
  const left = Math.min(band.startX - window.scrollX, band.x)
  const right = Math.max(band.startX - window.scrollX, band.x)
  const top = Math.min(band.startY - window.scrollY, band.y)
  const bottom = Math.max(band.startY - window.scrollY, band.y)
  const picked = new Set(band.base)
  for (const key of keysInBox(grid, left, top, right, bottom)) picked.add(key)
  select(picked)
}

/** A drag in progress: the pages moved, and before which page (index; the count for the end) they would land. */
interface Drag {
  keys: Set<string>
  slot: number | null
}

/** Before which page (index; the page count for the end) a drop at this point of the window would put pages. */
function slotAt(grid: HTMLElement | null, x: number, y: number): number | null {
  const cards = Array.from(grid?.querySelectorAll<HTMLElement>('.page-card[data-key]') ?? [])
  let nearest = -1
  let distance = Number.POSITIVE_INFINITY
  cards.forEach((card, i) => {
    // The card under the pointer, else the nearest one: a gap between cards, the space after the last row.
    const r = card.getBoundingClientRect()
    const dx = x < r.left ? r.left - x : x > r.right ? x - r.right : 0
    const dy = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0
    const d = Math.hypot(dx, dy)
    if (d < distance) {
      distance = d
      nearest = i
    }
  })
  if (nearest < 0) return null
  const r = cards[nearest].getBoundingClientRect()
  return x < r.left + r.width / 2 ? nearest : nearest + 1
}

export function PageGrid({
  pages,
  selectedKeys,
  busy,
  onReorder,
  onRotate,
  onDelete,
  onToggleSelect,
  onSelectKeys,
  onInsertBlankAfter,
  onExtract,
  onDuplicate,
  onEditText,
  onRedact,
  onOcr,
  onStraighten,
  onPrint,
  onSaveImage,
  onSaveExcel,
}: PageGridProps) {
  /** The page whose right-click menu is open, and where it was asked for. */
  const [menu, setMenu] = useState<{ key: string; x: number; y: number; note?: string } | null>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  // Moving pages by dragging them, with pointer events rather than the browser's drag and drop: "allow drag"
  // and right-click extensions stop drag events on their way to the page (as they do right clicks), and the
  // pages then did not move at all. The grid draws itself again only when the drop place changes; the pointer
  // and the drag are kept in refs as well, for the scrolling loop.
  const press = useRef<{ key: string; x: number; y: number; pointerId: number } | null>(null)
  const pointer = useRef({ x: 0, y: 0 })
  const [drag, setDragState] = useState<Drag | null>(null)
  const dragRef = useRef<Drag | null>(null)
  const showDrag = (next: Drag | null) => {
    dragRef.current = next
    setDragState(next)
  }
  // Picking pages as in the Windows file explorer:
  // - a click on a card picks that page alone, Ctrl+click adds or takes it away, Shift+click picks the pages from
  //   the last clicked one to it (Ctrl+Shift adds them);
  // - a box dragged from empty space (between, beside or under the cards) picks the pages it touches, and so does
  //   one dragged from a card with Ctrl or Shift held; a plain drag on a card still moves the page.
  // The box is followed at the window, as it may start outside the list (its margins, the space under it).
  const bandRef = useRef<Band | null>(null)
  const [band, setBand] = useState<Band | null>(null)
  /** The page clicked last: where a Shift+click range starts. */
  const anchor = useRef<string | null>(null)

  // The latest props, for the window's listeners below.
  const latest = useRef({ pages, selectedKeys, busy, onSelectKeys })
  useEffect(() => {
    latest.current = { pages, selectedKeys, busy, onSelectKeys }
  })

  /** A click (no drag) on a card, with the keys held. */
  function clickCard(key: string, ctrl: boolean, shift: boolean) {
    const { pages: list, selectedKeys: picked, onSelectKeys: select } = latest.current
    if (shift && anchor.current !== null && list.some((p) => p.key === anchor.current)) {
      const from = list.findIndex((p) => p.key === anchor.current)
      const to = list.findIndex((p) => p.key === key)
      const range = list.slice(Math.min(from, to), Math.max(from, to) + 1).map((p) => p.key)
      select(new Set(ctrl ? [...picked, ...range] : range))
      return
    }
    anchor.current = key
    if (ctrl) {
      const next = new Set(picked)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      select(next)
    } else {
      select(new Set([key]))
    }
  }

  useEffect(() => {
    const down = (e: PointerEvent) => {
      const grid = gridRef.current
      if (latest.current.busy || e.button !== 0 || e.pointerType === 'touch' || !grid || !grid.offsetParent) return
      const target = e.target as Element | null
      if (!target || latest.current.pages.length === 0) return
      if (target.closest('button, input, label, a, select, textarea, [role=dialog], .context-menu')) return
      const card = target.closest<HTMLElement>('.page-card[data-key]')
      const ctrl = e.ctrlKey || e.metaKey
      const shift = e.shiftKey
      let fromKey: string | undefined
      if (card && grid.contains(card)) {
        // A plain press on a card is a click or a page moved (the list's own handler).
        if (!ctrl && !shift) return
        fromKey = card.dataset.key
      } else if (!(target === grid || target.contains(grid))) {
        // Only empty space: the list itself, or what holds it (its margins); nothing else on the screen.
        return
      } else if (e.clientY < grid.getBoundingClientRect().top - 24) {
        return // above the list: the menus, the drop zone
      }
      const picked = latest.current.selectedKeys
      bandRef.current = {
        pointerId: e.pointerId,
        startX: e.clientX + window.scrollX,
        startY: e.clientY + window.scrollY,
        x: e.clientX,
        y: e.clientY,
        base: ctrl || shift ? new Set(picked) : new Set<string>(),
        before: new Set(picked),
        shown: false,
        fromKey,
        ctrl,
        shift,
      }
      if (fromKey !== undefined) e.preventDefault() // no text picked, no page moved
    }
    const move = (e: PointerEvent) => {
      const current = bandRef.current
      if (!current || e.pointerId !== current.pointerId) return
      if ((e.buttons & 1) === 0) {
        bandRef.current = null
        setBand(null)
        return
      }
      pointer.current = { x: e.clientX, y: e.clientY }
      const moved = Math.hypot(e.clientX + window.scrollX - current.startX, e.clientY + window.scrollY - current.startY)
      if (!current.shown && moved < DRAG_START) return
      if (!current.shown) setMenu(null)
      e.preventDefault()
      const next = { ...current, x: e.clientX, y: e.clientY, shown: true }
      bandRef.current = next
      setBand(next)
      pickInBand(gridRef.current, next, latest.current.onSelectKeys)
    }
    const up = (e: PointerEvent) => {
      const current = bandRef.current
      if (!current || e.pointerId !== current.pointerId) return
      bandRef.current = null
      setBand(null)
      if (current.shown) return
      // A click, not a box: on a card (Ctrl/Shift+click); in empty space without keys, nothing picked any more.
      if (current.fromKey !== undefined) clickCard(current.fromKey, current.ctrl, current.shift)
      else if (!current.ctrl && !current.shift && e.type === 'pointerup') latest.current.onSelectKeys(new Set())
    }
    window.addEventListener('pointerdown', down)
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', up)
    return () => {
      window.removeEventListener('pointerdown', down)
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', up)
    }
    // clickCard reads everything from refs.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // A right click on a page card opens the page's menu (taken at the window: see rightClick.ts).
  useRightClick((target, x, y, blocked) => {
    const card = target.closest<HTMLElement>('.page-card[data-key]')
    const key = card?.dataset.key
    if (!card || key === undefined || !gridRef.current?.contains(card)) return false
    setMenu({ key, x, y, note: blocked ? BLOCKED_NOTE : undefined })
    return true
  })

  function onPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    if (busy || e.button !== 0 || e.pointerType === 'touch') return
    if (e.ctrlKey || e.metaKey || e.shiftKey) return // picking, at the window
    const target = e.target as Element
    // The card's buttons and tick box do their own thing.
    if (target.closest('button, input, label, a, select, textarea')) return
    const key = target.closest<HTMLElement>('.page-card[data-key]')?.dataset.key
    if (key === undefined) return
    press.current = { key, x: e.clientX, y: e.clientY, pointerId: e.pointerId }
  }

  function onPointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    const pressed = press.current
    if (!pressed || e.pointerId !== pressed.pointerId) return
    if ((e.buttons & 1) === 0) {
      press.current = null // the button was let go outside the list
      return
    }
    pointer.current = { x: e.clientX, y: e.clientY }
    let current = dragRef.current
    if (!current) {
      if (Math.hypot(e.clientX - pressed.x, e.clientY - pressed.y) < DRAG_START) return
      // A picked page moves with the other picked pages; any other page moves alone.
      const keys = selectedKeys.has(pressed.key) ? new Set(selectedKeys) : new Set([pressed.key])
      e.currentTarget.setPointerCapture?.(e.pointerId)
      setMenu(null)
      current = { keys, slot: null }
    }
    e.preventDefault() // no text picked on the way
    const slot = slotAt(gridRef.current, e.clientX, e.clientY)
    if (current !== dragRef.current || slot !== current.slot) showDrag({ keys: current.keys, slot })
  }

  function finish(drop: boolean) {
    const current = dragRef.current
    const pressed = press.current
    press.current = null
    if (!current) {
      // Let go where it was pressed: a click on the card picks that page alone.
      if (drop && pressed) clickCard(pressed.key, false, false)
      return
    }
    showDrag(null)
    if (drop && current.slot !== null) onReorder(dropAt(pages, current.keys, current.slot))
  }

  // While a box is dragged out: the window scrolls near its top and bottom edge (the box grows with it), and
  // Esc gives it up.
  const boxing = band?.shown ?? false
  useEffect(() => {
    if (!boxing) return
    let frame = 0
    const step = () => {
      const current = bandRef.current
      if (!current) return
      const { y } = pointer.current
      const bottom = window.innerHeight - SCROLL_EDGE
      const speed = y < SCROLL_EDGE ? -(SCROLL_EDGE - y) / 4 : y > bottom ? (y - bottom) / 4 : 0
      if (speed !== 0) {
        window.scrollBy(0, speed)
        const next = { ...current }
        bandRef.current = next
        setBand(next)
        pickInBand(gridRef.current, next, latest.current.onSelectKeys)
      }
      frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    const escape = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopImmediatePropagation()
      const current = bandRef.current
      bandRef.current = null
      setBand(null)
      if (current) latest.current.onSelectKeys(current.before)
    }
    window.addEventListener('keydown', escape, true)
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('keydown', escape, true)
    }
  }, [boxing])

  // While dragging: the window scrolls near its top and bottom edge, and Esc puts the pages back.
  const dragging = drag !== null
  useEffect(() => {
    if (!dragging) return
    let frame = 0
    const step = () => {
      const current = dragRef.current
      if (!current) return
      const { x, y } = pointer.current
      const bottom = window.innerHeight - SCROLL_EDGE
      const speed = y < SCROLL_EDGE ? -(SCROLL_EDGE - y) / 4 : y > bottom ? (y - bottom) / 4 : 0
      if (speed !== 0) {
        window.scrollBy(0, speed)
        const slot = slotAt(gridRef.current, x, y)
        if (slot !== current.slot) {
          dragRef.current = { ...current, slot }
          setDragState(dragRef.current)
        }
      }
      frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    const escape = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopImmediatePropagation()
      press.current = null
      dragRef.current = null
      setDragState(null)
    }
    window.addEventListener('keydown', escape, true)
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('keydown', escape, true)
    }
  }, [dragging])

  /** What the card's buttons do, and the rest of what can be done to one page, in one menu. */
  function entriesFor(key: string): MenuEntry[] {
    const selected = selectedKeys.has(key)
    // Moves act on all picked pages when the page is one of them, else on the page alone.
    const moving = selected ? selectedKeys : new Set([key])
    const many = moving.size > 1 ? ` (고른 ${moving.size}쪽)` : ''
    const moveToFront = moveTo(pages, moving, 0)
    const moveToBack = moveTo(pages, moving, pages.length)
    return [
      { label: '텍스트 편집', shortcut: '더블클릭', disabled: busy, onSelect: () => onEditText(key) },
      { label: '가림 영역 그리기 (개인정보)', disabled: busy, onSelect: () => onRedact(key) },
      { label: '글자 인식 (OCR)', disabled: busy, onSelect: () => onOcr(key) },
      'separator',
      {
        label: `앞으로 한 칸${many}`,
        shortcut: selected ? 'Ctrl+←' : undefined,
        disabled: busy || !canMoveBy(pages, moving, -1),
        onSelect: () => onReorder(moveBy(pages, moving, -1)),
      },
      {
        label: `뒤로 한 칸${many}`,
        shortcut: selected ? 'Ctrl+→' : undefined,
        disabled: busy || !canMoveBy(pages, moving, 1),
        onSelect: () => onReorder(moveBy(pages, moving, 1)),
      },
      { label: '맨 앞으로', disabled: busy || moveToFront === pages, onSelect: () => onReorder(moveToFront) },
      { label: '맨 뒤로', disabled: busy || moveToBack === pages, onSelect: () => onReorder(moveToBack) },
      {
        label: '다른 자리로 옮기기…',
        disabled: busy || pages.length - moving.size < 1,
        onSelect: () => {
          const now = pages.findIndex((p) => moving.has(p.key)) + 1
          const answer = window.prompt(`몇 번째 자리로 옮길까요? (1~${pages.length})`, String(now))
          const number = answer === null ? Number.NaN : Number.parseInt(answer, 10)
          if (Number.isFinite(number)) onReorder(moveToNumber(pages, moving, number))
        },
      },
      'separator',
      { label: '왼쪽으로 회전', disabled: busy, onSelect: () => onRotate(key, -90) },
      { label: '오른쪽으로 회전', disabled: busy, onSelect: () => onRotate(key, 90) },
      { label: `방향·기울기 바로잡기 (스캔)${many}`, disabled: busy, onSelect: () => onStraighten(key) },
      'separator',
      { label: '페이지 복제 (고친 내용째로)', disabled: busy, onSelect: () => onDuplicate(key) },
      { label: '뒤에 빈 페이지 삽입', disabled: busy, onSelect: () => onInsertBlankAfter(key) },
      { label: '이 페이지만 추출 (PDF로 저장)', disabled: busy, onSelect: () => onExtract(key) },
      { label: '이 페이지 사진으로 저장…', disabled: busy, onSelect: () => onSaveImage(key) },
      { label: '이 페이지 엑셀 파일로 저장', disabled: busy, onSelect: () => onSaveExcel(key) },
      { label: '이 페이지 인쇄', disabled: busy, onSelect: () => onPrint(key) },
      'separator',
      { label: selected ? '선택 해제' : '선택', disabled: busy, onSelect: () => onToggleSelect(key) },
      { label: '페이지 삭제', disabled: busy, onSelect: () => onDelete(key) },
    ]
  }

  const menuIndex = menu ? pages.findIndex((p) => p.key === menu.key) : -1
  /** Where the bar showing the drop goes: before the page at the slot, or after the last page. */
  const dropBefore = drag?.slot !== null && drag?.slot !== undefined && drag.slot < pages.length ? drag.slot : -1
  const dropAfterLast = drag?.slot === pages.length

  return (
    <>
      <div
        className={`page-grid${dragging ? ' sorting' : ''}${boxing ? ' boxing' : ''}`}
        ref={gridRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={() => finish(true)}
        onPointerCancel={() => finish(false)}
      >
        {pages.map((page, index) => (
          <PageThumbnail
            key={page.key}
            page={page}
            index={index}
            isDragging={drag?.keys.has(page.key) ?? false}
            dropSide={index === dropBefore ? 'before' : dropAfterLast && index === pages.length - 1 ? 'after' : null}
            isSelected={selectedKeys.has(page.key)}
            busy={busy}
            onRotate={onRotate}
            onDelete={onDelete}
            onToggleSelect={onToggleSelect}
            onInsertBlankAfter={onInsertBlankAfter}
            onExtract={onExtract}
            onDuplicate={onDuplicate}
            onEditText={onEditText}
          />
        ))}
      </div>
      {band?.shown && (
        <div
          className="page-band"
          aria-hidden="true"
          style={{
            left: Math.min(band.startX - window.scrollX, band.x),
            top: Math.min(band.startY - window.scrollY, band.y),
            width: Math.abs(band.x - (band.startX - window.scrollX)),
            height: Math.abs(band.y - (band.startY - window.scrollY)),
          }}
        />
      )}
      {/* A page removed while its menu was open (by another action) takes the menu with it. */}
      {menu && menuIndex >= 0 && (
        <ContextMenu
          x={menu.x}
          y={menu.y}
          label={`${menuIndex + 1}쪽`}
          entries={entriesFor(menu.key)}
          note={menu.note}
          onClose={() => setMenu(null)}
        />
      )}
    </>
  )
}
