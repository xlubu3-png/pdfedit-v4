// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { areaStyle, changedPages, documentsIn, pairCount } from '../compare'
import type { CompareResult, PageItem } from '../types'

const api = vi.hoisted(() => ({
  compareDocuments: vi.fn(),
  compareLook: vi.fn(),
  originalUrl: vi.fn(() => 'about:blank'),
}))
vi.mock('../api', () => api)

import { CompareView } from './CompareView'

const page = (documentId: string, sourceFileName: string, pageIndex: number): PageItem => ({
  key: `${documentId}-${pageIndex}`,
  documentId,
  sourceFileName,
  pageIndex,
  rotation: 0,
  textRevision: 0,
})

const A4 = { width: 595, height: 842, originX: 0, originY: 0 }
const area = { x: 100, y: 700, width: 30, height: 14 }

const result: CompareResult = {
  a: 'old',
  b: 'new',
  fileNameA: 'old.pdf',
  fileNameB: 'new.pdf',
  pagesA: [A4, A4],
  pagesB: [A4, A4, A4],
  removed: [{ page: 1, text: '100', area }],
  added: [
    { page: 1, text: '120', area },
    { page: 2, text: 'Appendix', area },
  ],
  removedWords: 1,
  addedWords: 2,
}

afterEach(cleanup)
beforeEach(() => {
  vi.clearAllMocks()
  api.compareDocuments.mockResolvedValue(result)
  api.compareLook.mockResolvedValue({ comparable: true, areas: [area] })
})

describe('compare helpers', () => {
  it('lists each document once, in the order of the page list', () => {
    expect(documentsIn([page('x', 'x.pdf', 0), page('y', 'y.pdf', 0), page('x', 'x.pdf', 1)])).toEqual([
      { documentId: 'x', fileName: 'x.pdf', pages: 2 },
      { documentId: 'y', fileName: 'y.pdf', pages: 1 },
    ])
  })

  it('groups the changes by page and counts the pairs by the longer document', () => {
    expect(pairCount(result)).toBe(3)
    expect(changedPages(result).map((c) => [c.index, c.removed.length, c.added.length])).toEqual([
      [1, 1, 1],
      [2, 0, 1],
    ])
  })

  it('places an area on the picture of its page', () => {
    expect(areaStyle(area, A4, 2)).toEqual({ left: 200, top: (842 - 714) * 2, width: 60, height: 28 })
    expect(areaStyle(area, { ...A4, originX: 10, originY: 20 }, 1).left).toBe(90)
  })
})

describe('CompareView', () => {
  it('asks for two documents, then opens on the first changed page and walks the changes', async () => {
    render(<CompareView pages={[page('old', 'old.pdf', 0), page('new', 'new.pdf', 0)]} onClose={() => {}} />)

    fireEvent.click(screen.getByText('비교하기'))
    await screen.findByText('바뀐 쪽 2 · 삭제 1어절 · 추가 2어절')
    expect(api.compareDocuments).toHaveBeenCalledWith('old', 'new')
    expect(screen.getByText('2 / 3쪽')).toBeTruthy()
    expect(screen.getAllByTestId('cmp-removed')).toHaveLength(1)
    expect(screen.getAllByTestId('cmp-added')).toHaveLength(1)

    fireEvent.click(screen.getByTitle('다음 바뀐 쪽'))
    expect(screen.getByText('3 / 3쪽')).toBeTruthy()
    // The old version has no third page.
    expect(screen.getByText('이 문서에는 3쪽이 없습니다.')).toBeTruthy()
  })

  it('compares the look of the shown pages only when asked', async () => {
    render(<CompareView pages={[page('old', 'old.pdf', 0), page('new', 'new.pdf', 0)]} onClose={() => {}} />)
    fireEvent.click(screen.getByText('비교하기'))
    await screen.findByText(/바뀐 쪽 2/)
    expect(api.compareLook).not.toHaveBeenCalled()

    fireEvent.click(screen.getByLabelText('모양 차이'))

    await vi.waitFor(() => expect(api.compareLook).toHaveBeenCalledWith('old', 1, 'new', 1))
  })

  it('needs two different documents', () => {
    const { unmount } = render(<CompareView pages={[page('old', 'old.pdf', 0)]} onClose={() => {}} />)
    expect(screen.getByText(/두 개 이상 올리세요/)).toBeTruthy()
    unmount()

    render(<CompareView pages={[page('old', 'old.pdf', 0), page('new', 'new.pdf', 0)]} onClose={() => {}} />)
    fireEvent.change(screen.getByLabelText(/새 판/), { target: { value: 'old' } })
    expect((screen.getByText('비교하기') as HTMLButtonElement).disabled).toBe(true)
  })
})
