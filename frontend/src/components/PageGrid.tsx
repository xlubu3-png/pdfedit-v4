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
  onInsertBlankAfter: (key: string) => void
  onExtract: (key: string) => void
  onDuplicate: (key: string) => void
  onEditText: (key: string) => void
  /** Draws redaction areas on the page by hand. */
  onRedact: (key: string) => void
  /** Recognizes the page's text (a scan). */
  onOcr: (key: string) => void
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
  onInsertBlankAfter,
  onExtract,
  onDuplicate,
  onEditText,
  onRedact,
  onOcr,
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
    press.current = null
    if (!current) return
    showDrag(null)
    if (drop && current.slot !== null) onReorder(dropAt(pages, current.keys, current.slot))
  }

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
        className={`page-grid${dragging ? ' sorting' : ''}`}
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
