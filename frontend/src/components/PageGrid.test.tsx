// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PageItem } from '../types'
import { PageGrid } from './PageGrid'

afterEach(cleanup)

const pages: PageItem[] = [0, 1].map((i) => ({
  key: `doc-1-${i}`,
  documentId: 'doc-1',
  sourceFileName: '계약서.pdf',
  pageIndex: i,
  rotation: 0,
  textRevision: 0,
}))

function renderGrid({ busy = false, selected = [] as string[] } = {}) {
  const handlers = {
    onReorder: vi.fn(),
    onRotate: vi.fn(),
    onDelete: vi.fn(),
    onToggleSelect: vi.fn(),
    onInsertBlankAfter: vi.fn(),
    onExtract: vi.fn(),
    onDuplicate: vi.fn(),
    onEditText: vi.fn(),
    onRedact: vi.fn(),
    onOcr: vi.fn(),
    onPrint: vi.fn(),
  }
  const view = render(<PageGrid pages={pages} selectedKeys={new Set(selected)} busy={busy} {...handlers} />)
  const card = (i: number) => view.container.querySelectorAll('.page-card')[i]
  return { view, card, ...handlers }
}

describe('PageGrid right-click menu', () => {
  it('opens the menu of the page right-clicked instead of the browser menu', () => {
    const { card } = renderGrid()

    const browserMenuShown = fireEvent.contextMenu(card(1), { clientX: 100, clientY: 120 })

    expect(browserMenuShown).toBe(false)
    const menu = screen.getByRole('menu', { name: '2쪽' })
    expect(menu.textContent).toContain('텍스트 편집')
    expect(menu.textContent).toContain('페이지 삭제')
  })

  it('does what was picked to that page, and closes', () => {
    const { card, onRotate, onEditText, onRedact, onOcr, onPrint } = renderGrid()

    const pick = (i: number, name: RegExp) => {
      fireEvent.contextMenu(card(i))
      fireEvent.click(screen.getByRole('menuitem', { name }))
    }
    pick(1, /오른쪽으로 회전/)
    pick(0, /텍스트 편집/)
    pick(0, /가림 영역 그리기/)
    pick(1, /글자 인식/)
    pick(1, /이 페이지 인쇄/)

    expect(onRotate).toHaveBeenCalledWith('doc-1-1', 90)
    expect(onEditText).toHaveBeenCalledWith('doc-1-0')
    expect(onRedact).toHaveBeenCalledWith('doc-1-0')
    expect(onOcr).toHaveBeenCalledWith('doc-1-1')
    expect(onPrint).toHaveBeenCalledWith('doc-1-1')
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('still opens when a browser extension stops right clicks at the document', () => {
    const stop = (e: Event) => e.stopPropagation()
    document.addEventListener('contextmenu', stop, true)
    try {
      const { card } = renderGrid()

      const browserMenuShown = fireEvent.contextMenu(card(0))

      expect(browserMenuShown).toBe(false)
      expect(screen.getByRole('menu', { name: '1쪽' })).toBeTruthy()
    } finally {
      document.removeEventListener('contextmenu', stop, true)
    }
  })

  it('offers to unselect a selected page', () => {
    const { card, onToggleSelect } = renderGrid({ selected: ['doc-1-0'] })

    fireEvent.contextMenu(card(0))
    fireEvent.click(screen.getByRole('menuitem', { name: '선택 해제' }))

    expect(onToggleSelect).toHaveBeenCalledWith('doc-1-0')
  })

  it('shows the items disabled while something else is running', () => {
    const { card, onDelete } = renderGrid({ busy: true })

    fireEvent.contextMenu(card(0))
    const remove = screen.getByRole('menuitem', { name: '페이지 삭제' })
    fireEvent.click(remove)

    expect(remove).toHaveProperty('disabled', true)
    expect(onDelete).not.toHaveBeenCalled()
  })
})
