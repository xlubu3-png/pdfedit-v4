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
})
