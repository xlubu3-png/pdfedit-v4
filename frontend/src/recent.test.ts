import { describe, expect, it } from 'vitest'
import { MAX_RECENT, orphans, parseRecent, serializeRecent, titleOf, whenText, withWork } from './recent'
import type { PageItem } from './types'

const page = (key: string, documentId: string, sourceFileName: string): PageItem => ({
  key,
  documentId,
  sourceFileName,
  pageIndex: 0,
  rotation: 0,
  textRevision: 0,
})

describe('work put aside', () => {
  const quote = [page('a', 'd1', '견적서.pdf'), page('b', 'd1', '견적서.pdf'), page('c', 'd2', '도면.pdf')]

  it('is called by its first file and how many more', () => {
    expect(titleOf(quote)).toBe('견적서.pdf 외 1개')
    expect(titleOf(quote.slice(0, 2))).toBe('견적서.pdf')
  })

  it('goes to the top of the list, comes back as it was stored, and the oldest gives way', () => {
    let list = withWork([], quote, 1000).list
    expect(parseRecent(serializeRecent(list))).toEqual(list)
    expect(list[0]).toMatchObject({ title: '견적서.pdf 외 1개', savedAt: 1000 })

    let dropped: string[] = []
    for (let i = 0; i < MAX_RECENT; i++) {
      const next = withWork(list, [page(`k${i}`, `x${i}`, `f${i}.pdf`)], 2000 + i)
      list = next.list
      dropped = [...dropped, ...next.dropped.map((w) => w.title)]
    }
    expect(list).toHaveLength(MAX_RECENT)
    expect(list[0].title).toBe(`f${MAX_RECENT - 1}.pdf`)
    expect(dropped).toEqual(['견적서.pdf 외 1개'])
    expect(withWork(list, [], 1).list).toBe(list)
  })

  it('takes with it only the documents nothing else uses', () => {
    const [first] = withWork([], quote, 1).list
    const other = withWork([], [page('z', 'd2', '도면.pdf')], 2).list
    expect(orphans(first, [], [])).toEqual(['d1', 'd2'])
    expect(orphans(first, other, [])).toEqual(['d1'])
    expect(orphans(first, other, [page('q', 'd1', '견적서.pdf')])).toEqual([])
  })

  it('leaves out what is malformed', () => {
    expect(parseRecent('nonsense')).toEqual([])
    expect(parseRecent(JSON.stringify([{ id: 'a', title: 't', savedAt: 1, pages: '[]' }, 3, null]))).toEqual([])
  })

  it('says when it was put aside', () => {
    const now = new Date(2026, 10, 12, 15, 0).getTime()
    expect(whenText(new Date(2026, 10, 12, 9, 5).getTime(), now)).toBe('오늘 09:05')
    expect(whenText(new Date(2026, 10, 11, 23, 0).getTime(), now)).toBe('어제')
    expect(whenText(new Date(2026, 10, 9, 8, 0).getTime(), now)).toBe('3일 전')
    expect(whenText(new Date(2026, 9, 1, 8, 0).getTime(), now)).toBe('2026-10-01')
  })
})
