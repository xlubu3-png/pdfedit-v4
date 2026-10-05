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

function renderCard(busy: boolean, onEditText = vi.fn()) {
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
      onEditText={onEditText}
    />,
  )
  return { view, onEditText }
}

describe('PageThumbnail', () => {
  it('opens the text editor when the page picture is double-clicked', () => {
    const { view, onEditText } = renderCard(false)

    fireEvent.doubleClick(view.container.querySelector('img')!)

    expect(onEditText).toHaveBeenCalledWith('doc-1-0')
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
