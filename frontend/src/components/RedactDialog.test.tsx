// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PageItem, PersonalInfoMatch, Redaction } from '../types'

const api = vi.hoisted(() => ({
  findPersonalInfo: vi.fn(),
  fetchRedactions: vi.fn(),
  saveRedactions: vi.fn(),
}))
vi.mock('../api', () => api)

import { RedactDialog } from './RedactDialog'

const page = (documentId: string, pageIndex: number): PageItem => ({
  key: `${documentId}-${pageIndex}`,
  documentId,
  sourceFileName: 'a.pdf',
  pageIndex,
  rotation: 0,
  textRevision: 0,
})

const match = (pageIndex: number, text: string, x: number): PersonalInfoMatch => ({
  documentId: 'd',
  pageIndex,
  kind: 'MOBILE',
  label: '휴대전화번호',
  text,
  x,
  y: 700,
  width: 80,
  height: 14,
})

afterEach(cleanup)
beforeEach(() => {
  vi.resetAllMocks()
  api.fetchRedactions.mockResolvedValue([])
  api.saveRedactions.mockResolvedValue(undefined)
})

describe('RedactDialog', () => {
  it('finds, lets the user untick one, and saves the rest as areas of their pages', async () => {
    api.findPersonalInfo.mockResolvedValue([match(0, '010-1111-2222', 72), match(1, '010-3333-4444', 72), match(1, '010-5555-6666', 200)])
    const existing: Redaction = { id: 'old', x: 1, y: 1, width: 5, height: 5 }
    api.fetchRedactions.mockImplementation(async (_d: string, p: number) => (p === 1 ? [existing] : []))
    const onChanged = vi.fn()
    render(<RedactDialog pages={[page('d', 0), page('d', 1)]} onClose={() => {}} onDraw={() => {}} onChanged={onChanged} />)

    fireEvent.click(screen.getByText('찾기'))
    await screen.findByText('010-3333-4444')
    expect(api.findPersonalInfo.mock.calls[0][0]).toEqual([
      { documentId: 'd', pageIndex: 0 },
      { documentId: 'd', pageIndex: 1 },
    ])
    expect(api.findPersonalInfo.mock.calls[0][1]).not.toContain('BUSINESS')

    fireEvent.click(screen.getByText('010-5555-6666'))
    fireEvent.click(screen.getByText('선택한 2곳 가리기'))

    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(api.saveRedactions).toHaveBeenCalledTimes(2)
    const saved = new Map(api.saveRedactions.mock.calls.map((c) => [c[1] as number, c[2] as Redaction[]]))
    expect(saved.get(0)!.map((a) => a.x)).toEqual([72])
    // The page's area from before is kept, the new one added, the unticked one left out.
    expect(saved.get(1)!.map((a) => a.x)).toEqual([1, 72])
    expect(screen.getByText(/2곳을 가림 영역으로 넣었습니다/)).toBeTruthy()
  })

  it('opens the drawing view on the page of a finding', async () => {
    api.findPersonalInfo.mockResolvedValue([match(1, '010-3333-4444', 72)])
    const onDraw = vi.fn()
    render(<RedactDialog pages={[page('d', 0), page('d', 1)]} onClose={() => {}} onDraw={onDraw} onChanged={() => {}} />)

    fireEvent.click(screen.getByText('찾기'))
    fireEvent.click(await screen.findByText('2쪽'))

    expect(onDraw).toHaveBeenCalledWith('d-1')
  })

  it('says when nothing was found and shows the server error when the search fails', async () => {
    api.findPersonalInfo.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('서버 오류'))
    render(<RedactDialog pages={[page('d', 0)]} onClose={() => {}} onDraw={() => {}} onChanged={() => {}} />)

    fireEvent.click(screen.getByText('찾기'))
    await screen.findByText('찾은 항목이 없습니다.')
    fireEvent.click(screen.getByText('찾기'))
    await screen.findByText('서버 오류')
  })

  it('clears every page that has areas after asking', async () => {
    api.fetchRedactions.mockImplementation(async (_d: string, p: number) => (p === 0 ? [{ id: 'x', x: 1, y: 1, width: 2, height: 2 }] : []))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onChanged = vi.fn()
    render(<RedactDialog pages={[page('d', 0), page('d', 1)]} onClose={() => {}} onDraw={() => {}} onChanged={onChanged} />)

    fireEvent.click(screen.getByText('가림 모두 지우기'))

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith([{ documentId: 'd', pageIndex: 0 }]))
    expect(api.saveRedactions).toHaveBeenCalledWith('d', 0, [])
  })
})
