// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PageItem } from '../types'
import { PageThumbnail } from './PageThumbnail'

afterEach(cleanup)

const page: PageItem = {
  key: 'doc-1-0',
  documentId: 'doc-1',
  sourceFileName: '계약서.pdf',
  pageIndex: 0,
  rotation: 0,
  textRevision: 0,
}

function renderCard(busy: boolean, onEditText = vi.fn(), onDuplicate = vi.fn()) {
  const noop = () => {}
  const view = render(
    <PageThumbnail
      page={page}
      index={0}
      isDragging={false}
      isSelected={false}
      busy={busy}
      onDragStart={noop}
      onDragOver={noop}
      onDrop={noop}
      onRotate={noop}
      onDelete={noop}
      onToggleSelect={noop}
      onInsertBlankAfter={noop}
      onExtract={noop}
      onDuplicate={onDuplicate}
      onEditText={onEditText}
    />,
  )
  return { view, onEditText, onDuplicate }
}

describe('PageThumbnail', () => {
  it('opens the text editor when the page picture is double-clicked', () => {
    const { view, onEditText } = renderCard(false)

    fireEvent.doubleClick(view.container.querySelector('img')!)

    expect(onEditText).toHaveBeenCalledWith('doc-1-0')
  })

  it('duplicates the page from its card', () => {
    const { view, onDuplicate } = renderCard(false)

    fireEvent.click(view.container.querySelector('button[title^="페이지 복제"]')!)

    expect(onDuplicate).toHaveBeenCalledWith('doc-1-0')
  })

  it('does nothing while the app is busy', () => {
    const { view, onEditText } = renderCard(true)

    fireEvent.doubleClick(view.container.querySelector('img')!)

    expect(onEditText).not.toHaveBeenCalled()
  })

  it('does not open the editor from a double-click on the buttons below the picture', () => {
    const { view, onEditText } = renderCard(false)

    fireEvent.doubleClick(view.container.querySelector('button[title="오른쪽으로 회전"]')!)

    expect(onEditText).not.toHaveBeenCalled()
  })
})
