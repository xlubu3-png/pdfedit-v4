import type { DragEvent } from 'react'
import { thumbnailUrl } from '../api'
import type { PageItem } from '../types'
import {
  DeleteIcon,
  DuplicateIcon,
  EditTextIcon,
  ExtractIcon,
  InsertPageIcon,
  RotateLeftIcon,
  RotateRightIcon,
} from './icons'

interface PageThumbnailProps {
  page: PageItem
  index: number
  isDragging: boolean
  isSelected: boolean
  busy: boolean
  onDragStart: (index: number) => void
  onDragOver: (index: number) => void
  onDrop: () => void
  onRotate: (key: string, delta: 90 | -90) => void
  onDelete: (key: string) => void
  onToggleSelect: (key: string) => void
  onInsertBlankAfter: (key: string) => void
  onExtract: (key: string) => void
  onDuplicate: (key: string) => void
  onEditText: (key: string) => void
}

export function PageThumbnail({
  page,
  index,
  isDragging,
  isSelected,
  busy,
  onDragStart,
  onDragOver,
  onDrop,
  onRotate,
  onDelete,
  onToggleSelect,
  onInsertBlankAfter,
  onExtract,
  onDuplicate,
  onEditText,
}: PageThumbnailProps) {
  return (
    <div
      className={`page-card${isDragging ? ' dragging' : ''}${isSelected ? ' selected' : ''}`}
      draggable={!busy}
      onDragStart={() => onDragStart(index)}
      onDragOver={(e: DragEvent<HTMLDivElement>) => {
        e.preventDefault()
        onDragOver(index)
      }}
      onDrop={(e: DragEvent<HTMLDivElement>) => {
        e.preventDefault()
        onDrop()
      }}
    >
      <div className="page-number">{index + 1}</div>
      {page.ocrLines !== undefined && page.ocrLines > 0 && (
        <div
          className="page-ocr"
          title={`스캔한 쪽에서 글자 ${page.ocrLines}줄을 인식했습니다. 내보낸 PDF에서 검색·복사할 수 있습니다.`}
        >
          OCR
        </div>
      )}
      <label className="page-select" title="선택">
        <input type="checkbox" checked={isSelected} disabled={busy} onChange={() => onToggleSelect(page.key)} />
      </label>
      <div
        className="thumbnail-frame"
        title="더블클릭하면 이 페이지의 텍스트를 편집합니다"
        onDoubleClick={() => {
          if (!busy) onEditText(page.key)
        }}
      >
        <img
          src={thumbnailUrl(page.documentId, page.pageIndex, 240, page.textRevision)}
          alt={`${page.sourceFileName} p.${page.pageIndex + 1}`}
          style={{ transform: `rotate(${page.rotation}deg)` }}
        />
      </div>
      <div className="page-actions">
        <button type="button" title="왼쪽으로 회전" disabled={busy} onClick={() => onRotate(page.key, -90)}>
          <RotateLeftIcon />
        </button>
        <button type="button" title="오른쪽으로 회전" disabled={busy} onClick={() => onRotate(page.key, 90)}>
          <RotateRightIcon />
        </button>
        <button type="button" title="텍스트 편집" disabled={busy} onClick={() => onEditText(page.key)}>
          <EditTextIcon />
        </button>
        <button type="button" title="이 페이지만 추출" disabled={busy} onClick={() => onExtract(page.key)}>
          <ExtractIcon />
        </button>
        <button type="button" title="페이지 복제 (Ctrl+D): 고친 내용째로 바로 뒤에 복사" disabled={busy} onClick={() => onDuplicate(page.key)}>
          <DuplicateIcon />
        </button>
        <button type="button" title="뒤에 빈 페이지 삽입" disabled={busy} onClick={() => onInsertBlankAfter(page.key)}>
          <InsertPageIcon />
        </button>
        <button
          type="button"
          title="페이지 삭제"
          className="danger"
          disabled={busy}
          onClick={() => onDelete(page.key)}
        >
          <DeleteIcon />
        </button>
      </div>
      <div className="page-source" title={page.sourceFileName}>
        {page.sourceFileName}
      </div>
    </div>
  )
}
