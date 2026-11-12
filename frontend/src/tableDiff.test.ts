import { describe, expect, it } from 'vitest'
import { compareTables } from './tableDiff'
import { gridTable } from './tableGrid'
import type { Table } from './tableGrid'

function table(rows: string[][]): Table {
  const cols = Math.max(...rows.map((r) => r.length))
  const t = gridTable({ xs: Array.from({ length: cols + 1 }, (_, i) => i * 100), ys: rows.map((_, i) => i * 20).concat(rows.length * 20) })
  for (const cell of t.cells) {
    const text = rows[cell.row][cell.col] ?? ''
    if (text) cell.texts.push({ text, left: cell.left + 5, top: cell.top + 4, height: 12, width: text.length * 5 })
  }
  return t
}

describe('compareTables', () => {
  const first = table([
    ['NO', '품명', '수량', '단가', '금액'],
    ['1', '팬 모터', '2', '110,150', '220,300'],
    ['2', '필터', '3', '6,590', '19,770'],
    ['3', '문 스위치', '1', '8,000', '8,000'],
    ['', '합계', '', '', '248,070'],
  ])
  const second = table([
    ['NO', '품 명', '수량', '단가', '금액'],
    ['1', '팬 모터', '2', '120,000', '240,000원'],
    ['2', '필터', '3', '6,590', '19,770'],
    ['3', 'LED 조명', '1', '15,000', '15,000'],
    ['', '합계', '', '', '274,770'],
  ])

  it('says which item\'s value changed, and which items were added or taken out', () => {
    const { changes, compared } = compareTables([first], [second])
    expect(compared).toBe(true)
    expect(changes).toEqual([
      { kind: 'changed', item: '팬 모터', column: '단가', before: '110,150', after: '120,000' },
      { kind: 'changed', item: '팬 모터', column: '금액', before: '220,300', after: '240,000원' },
      { kind: 'removed', item: '문 스위치', column: '', before: '1 · 8,000 · 8,000', after: '' },
      { kind: 'changed', item: '합계', column: '금액', before: '248,070', after: '274,770' },
      { kind: 'added', item: 'LED 조명', column: '', before: '', after: '1 · 15,000 · 15,000' },
    ])
  })

  it('finds nothing changed in the same table, and says when there is no item table', () => {
    expect(compareTables([first], [first]).changes).toEqual([])
    expect(compareTables([table([['a', 'b']])], [first]).compared).toBe(false)
  })

  it('pairs rows of the same name by what else is the same: the size, not the order', () => {
    const before = table([
      ['품명', '규격', '수량', '단가'],
      ['앵글', '30x30', '10', '1,000'],
      ['앵글', '50x50', '4', '2,500'],
    ])
    // The new offer lists the larger one first, dearer, and adds a third size.
    const after = table([
      ['품명', '규격', '수량', '단가'],
      ['앵글', '50x50', '4', '2,800'],
      ['앵글', '30x30', '10', '1,000'],
      ['앵글', '75x75', '2', '4,000'],
    ])
    expect(compareTables([before], [after]).changes).toEqual([
      { kind: 'changed', item: '앵글 (2)', column: '단가', before: '2,500', after: '2,800' },
      { kind: 'added', item: '앵글 (3)', column: '', before: '', after: '75x75 · 2 · 4,000' },
    ])
  })

  it('compares every table: an item named in two tables is not lost to the later one', () => {
    const labour = (cost: string) => table([['항목', '금액'], ['설치비', cost]])
    const parts = (cost: string) => table([['품명', '금액'], ['운반비', cost], ['설치비', '50,000']])
    expect(compareTables([labour('100,000'), parts('30,000')], [labour('120,000'), parts('30,000')]).changes).toEqual([
      { kind: 'changed', item: '설치비 (1)', column: '금액', before: '100,000', after: '120,000' },
    ])
  })
})
