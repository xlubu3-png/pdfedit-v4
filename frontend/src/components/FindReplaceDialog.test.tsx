// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PageItem } from '../types'
import { FindReplaceDialog } from './FindReplaceDialog'

vi.mock('../api', () => ({
  findText: vi.fn().mockResolvedValue([
    { documentId: 'doc', pageIndex: 0, runIndex: 2, text: '연락처 010-2345-6789', scanned: false },
    { documentId: 'doc', pageIndex: 0, runIndex: -1, text: '휴대전화 010-2345-6789', scanned: true },
  ]),
  replaceText: vi.fn(),
}))

afterEach(cleanup)

const pages: PageItem[] = [
  { key: 'doc-0', documentId: 'doc', sourceFileName: 'scan.pdf', pageIndex: 0, rotation: 0, textRevision: 0 },
]

describe('FindReplaceDialog', () => {
  it('marks lines read off a scan, which can be found but not replaced', async () => {
    render(<FindReplaceDialog pages={pages} onClose={vi.fn()} onOpenPage={vi.fn()} onReplaced={vi.fn()} />)

    fireEvent.change(screen.getByLabelText('찾을 글자'), { target: { value: '6789' } })
    fireEvent.click(screen.getByText('찾기'))

    const items = await screen.findAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0].querySelector('.find-scanned')).toBeNull()
    const tag = items[1].querySelector('.find-scanned')!
    expect(tag.textContent).toBe('스캔')
    expect(tag.getAttribute('title')).toContain('바꾸기는 되지 않습니다')
  })
})
