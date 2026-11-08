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
    onStraighten: vi.fn(),
    onPrint: vi.fn(),
    onSaveImage: vi.fn(),
    onSaveExcel: vi.fn(),
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
    const { card, onRotate, onEditText, onRedact, onOcr, onStraighten, onPrint, onSaveImage, onSaveExcel } = renderGrid()

    const pick = (i: number, name: RegExp) => {
      fireEvent.contextMenu(card(i))
      fireEvent.click(screen.getByRole('menuitem', { name }))
    }
    pick(1, /오른쪽으로 회전/)
    pick(0, /텍스트 편집/)
    pick(0, /가림 영역 그리기/)
    pick(1, /글자 인식/)
    pick(0, /기울기 바로잡기/)
    pick(1, /이 페이지 인쇄/)
    pick(0, /사진으로 저장/)
    pick(1, /엑셀 파일로 저장/)

    expect(onRotate).toHaveBeenCalledWith('doc-1-1', 90)
    expect(onEditText).toHaveBeenCalledWith('doc-1-0')
    expect(onRedact).toHaveBeenCalledWith('doc-1-0')
    expect(onOcr).toHaveBeenCalledWith('doc-1-1')
    expect(onStraighten).toHaveBeenCalledWith('doc-1-0')
    expect(onPrint).toHaveBeenCalledWith('doc-1-1')
    expect(onSaveImage).toHaveBeenCalledWith('doc-1-0')
    expect(onSaveExcel).toHaveBeenCalledWith('doc-1-1')
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

describe('PageGrid moving pages', () => {
  const three: PageItem[] = [0, 1, 2].map((i) => ({
    key: `doc-2-${i}`,
    documentId: 'doc-2',
    sourceFileName: '보고서.pdf',
    pageIndex: i,
    rotation: 0,
    textRevision: 0,
  }))
  const order = (list: PageItem[]) => list.map((p) => p.pageIndex + 1).join('')

  function renderThree(selected: string[] = []) {
    const onReorder = vi.fn()
    const noop = () => {}
    const view = render(
      <PageGrid
        pages={three}
        selectedKeys={new Set(selected)}
        busy={false}
        onReorder={onReorder}
        onRotate={noop}
        onDelete={noop}
        onToggleSelect={noop}
        onInsertBlankAfter={noop}
        onExtract={noop}
        onDuplicate={noop}
        onEditText={noop}
        onRedact={noop}
        onOcr={noop}
        onStraighten={noop}
        onPrint={noop}
        onSaveImage={noop}
        onSaveExcel={noop}
      />,
    )
    const cards = Array.from(view.container.querySelectorAll<HTMLElement>('.page-card'))
    // Cards side by side, 180 px wide with 20 px between them.
    cards.forEach((card, i) => {
      card.getBoundingClientRect = () =>
        ({ left: i * 200, right: i * 200 + 180, top: 0, bottom: 260, width: 180, height: 260, x: i * 200, y: 0 }) as DOMRect
    })
    return { cards, onReorder }
  }

  it('moves a page to the front, the back, or one place from its menu', () => {
    const { cards, onReorder } = renderThree()

    fireEvent.contextMenu(cards[2])
    fireEvent.click(screen.getByRole('menuitem', { name: '맨 앞으로' }))
    fireEvent.contextMenu(cards[0])
    fireEvent.click(screen.getByRole('menuitem', { name: /뒤로 한 칸/ }))
    fireEvent.contextMenu(cards[0])

    expect(onReorder.mock.calls.map(([list]) => order(list))).toEqual(['312', '213'])
    // Already first: it cannot go further forward.
    expect(screen.getByRole('menuitem', { name: '맨 앞으로' })).toHaveProperty('disabled', true)
  })

  it('moves all picked pages together when one of them is right-clicked', () => {
    const { cards, onReorder } = renderThree(['doc-2-1', 'doc-2-2'])

    fireEvent.contextMenu(cards[2])
    fireEvent.click(screen.getByRole('menuitem', { name: /앞으로 한 칸 \(고른 2쪽\)/ }))

    expect(order(onReorder.mock.calls[0][0])).toBe('231')
  })

  it('moves a page dragged with the mouse before the page it is let go on', () => {
    const { cards, onReorder } = renderThree()
    const grid = cards[0].parentElement!

    fireEvent.pointerDown(cards[2], { button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', clientX: 490, clientY: 100 })
    fireEvent.pointerMove(grid, { buttons: 1, pointerId: 1, pointerType: 'mouse', clientX: 20, clientY: 100 })
    expect(cards[0].classList.contains('drop-before')).toBe(true)
    expect(cards[2].classList.contains('dragging')).toBe(true)
    fireEvent.pointerUp(grid, { pointerId: 1, pointerType: 'mouse', clientX: 20, clientY: 100 })

    expect(order(onReorder.mock.calls[0][0])).toBe('312')
    expect(cards[2].classList.contains('dragging')).toBe(false)
  })

  it('leaves a click, a press on a button and a drag let go with Esc as they were', () => {
    const { cards, onReorder } = renderThree()
    const grid = cards[0].parentElement!

    fireEvent.pointerDown(cards[1], { button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', clientX: 290, clientY: 100 })
    fireEvent.pointerMove(grid, { buttons: 1, pointerId: 1, pointerType: 'mouse', clientX: 292, clientY: 101 })
    fireEvent.pointerUp(grid, { pointerId: 1, pointerType: 'mouse', clientX: 292, clientY: 101 })

    fireEvent.pointerDown(cards[1].querySelector('button')!, { button: 0, buttons: 1, pointerId: 2, pointerType: 'mouse', clientX: 290, clientY: 240 })
    fireEvent.pointerMove(grid, { buttons: 1, pointerId: 2, pointerType: 'mouse', clientX: 20, clientY: 100 })
    fireEvent.pointerUp(grid, { pointerId: 2, pointerType: 'mouse' })

    fireEvent.pointerDown(cards[1], { button: 0, buttons: 1, pointerId: 3, pointerType: 'mouse', clientX: 290, clientY: 100 })
    fireEvent.pointerMove(grid, { buttons: 1, pointerId: 3, pointerType: 'mouse', clientX: 20, clientY: 100 })
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.pointerUp(grid, { pointerId: 3, pointerType: 'mouse' })

    expect(onReorder).not.toHaveBeenCalled()
  })
})
