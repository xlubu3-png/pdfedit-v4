import { describe, expect, it } from 'vitest'
import { canMoveBy, dropAt, moveBy, moveTo, moveToNumber } from './pageOrder'
import type { PageItem } from './types'

const pages: PageItem[] = [1, 2, 3, 4, 5].map((n) => ({
  key: `p${n}`,
  documentId: 'doc',
  sourceFileName: 'a.pdf',
  pageIndex: n - 1,
  rotation: 0,
  textRevision: 0,
}))
const order = (list: PageItem[]) => list.map((p) => p.key.slice(1)).join('')
const set = (...keys: string[]) => new Set(keys)

describe('moving pages', () => {
  it('drops a page before another, or at the end', () => {
    expect(order(dropAt(pages, set('p5'), 0))).toBe('51234')
    expect(order(dropAt(pages, set('p1'), 3))).toBe('23145')
    expect(order(dropAt(pages, set('p1'), 5))).toBe('23451')
  })

  it('moves picked pages together, keeping their order', () => {
    expect(order(dropAt(pages, set('p2', 'p4'), 0))).toBe('24135')
    expect(order(moveTo(pages, set('p1', 'p3'), 3))).toBe('24513')
  })

  it('moves one place, pages at the end staying', () => {
    expect(order(moveBy(pages, set('p3'), -1))).toBe('13245')
    expect(order(moveBy(pages, set('p3'), 1))).toBe('12435')
    expect(order(moveBy(pages, set('p1', 'p2', 'p4'), -1))).toBe('12435')
    expect(order(moveBy(pages, set('p5', 'p3'), 1))).toBe('12435')
  })

  it('gives back the same list when nothing changes: no empty undo step', () => {
    expect(moveBy(pages, set('p1'), -1)).toBe(pages)
    expect(dropAt(pages, set('p2'), 1)).toBe(pages)
    expect(dropAt(pages, set('p2'), 2)).toBe(pages)
    expect(moveTo(pages, set(), 0)).toBe(pages)
    expect(canMoveBy(pages, set('p5'), 1)).toBe(false)
    expect(canMoveBy(pages, set('p5'), -1)).toBe(true)
  })

  it('puts pages at a page number, kept within the list', () => {
    expect(order(moveToNumber(pages, set('p5'), 2))).toBe('15234')
    expect(order(moveToNumber(pages, set('p1'), 99))).toBe('23451')
    expect(order(moveToNumber(pages, set('p3'), 0))).toBe('31245')
  })
})
