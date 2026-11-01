import { useRef, useState } from 'react'
import { BLOCKED_NOTE, useRightClick } from '../rightClick'
import type { PageItem } from '../types'
import { ContextMenu } from './ContextMenu'
import type { MenuEntry } from './ContextMenu'
import { PageThumbnail } from './PageThumbnail'

interface PageGridProps {
  pages: PageItem[]
  selectedKeys: Set<string>
  busy: boolean
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
}: PageGridProps) {
  const [draggingIndex, setDraggingIndex] = useState<number | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)
  /** The page whose right-click menu is open, and where it was asked for. */
  const [menu, setMenu] = useState<{ key: string; x: number; y: number; note?: string } | null>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  // A right click on a page card opens the page's menu (taken at the window: see rightClick.ts).
  useRightClick((target, x, y, blocked) => {
    const card = target.closest<HTMLElement>('.page-card[data-key]')
    const key = card?.dataset.key
    if (!card || key === undefined || !gridRef.current?.contains(card)) return false
    setMenu({ key, x, y, note: blocked ? BLOCKED_NOTE : undefined })
    return true
  })

  function handleDrop() {
    if (draggingIndex === null || overIndex === null || draggingIndex === overIndex) {
      setDraggingIndex(null)
      setOverIndex(null)
      return
    }
    const next = [...pages]
    const [moved] = next.splice(draggingIndex, 1)
    next.splice(overIndex, 0, moved)
    onReorder(next)
    setDraggingIndex(null)
    setOverIndex(null)
  }

  /** What the card's buttons do, and the rest of what can be done to one page, in one menu. */
  function entriesFor(key: string): MenuEntry[] {
    const selected = selectedKeys.has(key)
    return [
      { label: '텍스트 편집', shortcut: '더블클릭', disabled: busy, onSelect: () => onEditText(key) },
      { label: '가림 영역 그리기 (개인정보)', disabled: busy, onSelect: () => onRedact(key) },
      { label: '글자 인식 (OCR)', disabled: busy, onSelect: () => onOcr(key) },
      'separator',
      { label: '왼쪽으로 회전', disabled: busy, onSelect: () => onRotate(key, -90) },
      { label: '오른쪽으로 회전', disabled: busy, onSelect: () => onRotate(key, 90) },
      'separator',
      { label: '페이지 복제 (고친 내용째로)', disabled: busy, onSelect: () => onDuplicate(key) },
      { label: '뒤에 빈 페이지 삽입', disabled: busy, onSelect: () => onInsertBlankAfter(key) },
      { label: '이 페이지만 추출 (PDF로 저장)', disabled: busy, onSelect: () => onExtract(key) },
      { label: '이 페이지 인쇄', disabled: busy, onSelect: () => onPrint(key) },
      'separator',
      { label: selected ? '선택 해제' : '선택', disabled: busy, onSelect: () => onToggleSelect(key) },
      { label: '페이지 삭제', disabled: busy, onSelect: () => onDelete(key) },
    ]
  }

  const menuIndex = menu ? pages.findIndex((p) => p.key === menu.key) : -1

  return (
    <>
      <div className="page-grid" ref={gridRef}>
        {pages.map((page, index) => (
          <PageThumbnail
            key={page.key}
            page={page}
            index={index}
            isDragging={draggingIndex === index}
            isSelected={selectedKeys.has(page.key)}
            busy={busy}
            onDragStart={setDraggingIndex}
            onDragOver={setOverIndex}
            onDrop={handleDrop}
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
