import { describe, expect, it } from 'vitest'
import { changed, HISTORY_LIMIT, redone, undone, updated } from './pageHistory'
import type { PageListState } from './pageHistory'
import type { PageItem } from './types'

const page = (n: number, patch: Partial<PageItem> = {}): PageItem => ({
  key: `doc-${n}`,
  documentId: 'doc',
  sourceFileName: '계약서.pdf',
  pageIndex: n,
  rotation: 0,
  textRevision: 0,
  ...patch,
})

const start = (pages: PageItem[]): PageListState => ({ pages, undo: [], redo: [] })
const keys = (s: PageListState) => s.pages.map((p) => p.key)

describe('page list history', () => {
  it('takes a deleted page back, and deletes it again', () => {
    const s0 = start([page(0), page(1), page(2)])
    const s1 = changed(s0, (prev) => prev.filter((p) => p.key !== 'doc-1'))

    const back = undone(s1)
    expect(keys(back)).toEqual(['doc-0', 'doc-1', 'doc-2'])
    expect(keys(redone(back))).toEqual(['doc-0', 'doc-2'])
  })

  it('undoes moves and turns step by step, and a new change forgets what could be redone', () => {
    let s = start([page(0), page(1)])
    s = changed(s, (prev) => [prev[1], prev[0]])
    s = changed(s, (prev) => prev.map((p) => (p.key === 'doc-0' ? { ...p, rotation: 90 } : p)))

    s = undone(s)
    expect(s.pages.map((p) => [p.key, p.rotation])).toEqual([['doc-1', 0], ['doc-0', 0]])
    s = undone(s)
    expect(keys(s)).toEqual(['doc-0', 'doc-1'])
    expect(undone(s)).toBe(s)

    s = changed(s, (prev) => prev.slice(1))
    expect(s.redo).toEqual([])
    expect(redone(s)).toBe(s)
  })

  it('keeps what was learned of a page since: its edits and recognized lines are no step of their own', () => {
    let s = start([page(0), page(1)])
    s = changed(s, (prev) => prev.map((p) => (p.key === 'doc-0' ? { ...p, rotation: 90 } : p)))
    s = updated(s, (prev) => prev.map((p) => (p.key === 'doc-0' ? { ...p, textRevision: 3, ocrLines: 12 } : p)))
    expect(s.undo).toHaveLength(1)

    s = undone(s)
    expect(s.pages[0]).toMatchObject({ key: 'doc-0', rotation: 0, textRevision: 3, ocrLines: 12 })
  })

  it('remembers no more than its limit, and a change that changes nothing is no step', () => {
    let s = start([page(0)])
    for (let i = 0; i < HISTORY_LIMIT + 10; i++) {
      s = changed(s, (prev) => prev.map((p) => ({ ...p, rotation: ((p.rotation + 90) % 360) as PageItem['rotation'] })))
    }
    expect(s.undo).toHaveLength(HISTORY_LIMIT)
    expect(changed(s, (prev) => prev)).toBe(s)
  })
})
