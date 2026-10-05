import { useState } from 'react'
import type { PageItem } from '../types'
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
  onEditText: (key: string) => void
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
  onEditText,
}: PageGridProps) {
  const [draggingIndex, setDraggingIndex] = useState<number | null>(null)
  const [overIndex, setOverIndex] = useState<number | null>(null)

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

  return (
    <div className="page-grid">
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
          onEditText={onEditText}
        />
      ))}
    </div>
  )
}
