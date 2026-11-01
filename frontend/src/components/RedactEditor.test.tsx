// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PageItem } from '../types'

const api = vi.hoisted(() => ({
  fetchPageText: vi.fn(),
  fetchRedactions: vi.fn(),
  saveRedactions: vi.fn(),
  previewUrl: vi.fn(() => 'data:,'),
}))
vi.mock('../api', () => api)

const { RedactEditor } = await import('./RedactEditor')

const pages: PageItem[] = [0, 1].map((i) => ({
  key: `doc-${i}`,
  documentId: 'doc',
  sourceFileName: '계약서.pdf',
  pageIndex: i,
  rotation: 0,
  textRevision: 0,
}))

beforeEach(() => {
  api.fetchPageText.mockResolvedValue({ pageWidth: 595, pageHeight: 842, originX: 0, originY: 0, runs: [] })
  api.fetchRedactions.mockResolvedValue([])
  Element.prototype.setPointerCapture ??= () => {}
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

/** Draws an area by dragging over the page shown. */
async function drawArea() {
  const layer = await screen.findByTestId('redact-layer')
  layer.getBoundingClientRect = () => ({ left: 0, top: 0, width: 944, height: 1336, right: 944, bottom: 1336 }) as DOMRect
  fireEvent.pointerDown(layer, { button: 0, pointerId: 1, clientX: 100, clientY: 100 })
  fireEvent.pointerMove(layer, { pointerId: 1, clientX: 300, clientY: 200 })
  fireEvent.pointerUp(layer, { pointerId: 1, clientX: 300, clientY: 200 })
}

describe('RedactEditor', () => {
  it("does not put a page's areas on the next page when its save fails after moving on", async () => {
    let fail: (e: Error) => void = () => {}
    api.saveRedactions.mockReturnValueOnce(new Promise((_, reject) => (fail = reject)))
    render(<RedactEditor pages={pages} startKey="doc-0" onClose={() => {}} onChanged={() => {}} />)

    await drawArea()
    expect(screen.getAllByTestId('redact-box')).toHaveLength(1)
    fireEvent.click(screen.getByTitle('다음 페이지'))
    await screen.findByTestId('redact-layer')
    await act(async () => fail(new Error('Failed to fetch')))

    expect(screen.queryAllByTestId('redact-box')).toHaveLength(0)
    expect(screen.getByText(/계약서\.pdf 1쪽의 가림 영역을 저장하지 못했습니다/)).toBeTruthy()
  })

  it('takes a failed area back on the page it was drawn on', async () => {
    api.saveRedactions.mockRejectedValueOnce(new Error('Failed to fetch'))
    render(<RedactEditor pages={pages} startKey="doc-0" onClose={() => {}} onChanged={() => {}} />)

    await drawArea()
    await act(async () => {})

    expect(screen.queryAllByTestId('redact-box')).toHaveLength(0)
    expect(screen.getByText('저장하지 못했습니다')).toBeTruthy()
  })
})
