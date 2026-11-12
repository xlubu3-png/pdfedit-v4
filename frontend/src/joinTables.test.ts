import { describe, expect, it } from 'vitest'
import { continues, joinedTables } from './joinTables'
import { gridTable, toSheet, toTsv } from './tableGrid'
import type { Table } from './tableGrid'

/** A table at the page's columns 50/150/250/350 with these rows, starting `top` points down the page. */
function table(rows: string[][], top = 100, xs = [50, 150, 250, 350]): Table {
  const t = gridTable({ xs, ys: rows.map((_, i) => top + i * 20).concat(top + rows.length * 20) })
  for (const cell of t.cells) {
    const text = rows[cell.row][cell.col] ?? ''
    if (text) cell.texts.push({ text, left: cell.left + 5, top: cell.top + 4, height: 12, width: text.length * 5 })
  }
  return t
}

describe('joinedTables', () => {
  const head = ['품명', '수량', '금액']

  it('joins an item list running over three pages, leaving out the heading repeated on each', () => {
    const joined = joinedTables([
      [table([['견적서'], ['수신', '(주)가나', '']], 40, [50, 200, 350]), table([head, ['A', '1', '100'], ['B', '2', '200']])],
      [table([head, ['C', '3', '300']], 60)],
      [table([['D', '4', '400'], ['합계', '', '1,000']], 60)],
    ])
    expect(joined).toHaveLength(1)
    expect(joined[0].pages).toEqual([0, 1, 2])
    const tsv = toTsv([{ kind: 'table', table: joined[0].table, top: 0 }]).split('\n')
    expect(tsv).toEqual(['품명\t수량\t금액', 'A\t1\t100', 'B\t2\t200', 'C\t3\t300', 'D\t4\t400', '합계\t\t1,000'])
    expect(toSheet([{ kind: 'table', table: joined[0].table, top: 0 }]).heights).toHaveLength(6)
  })

  it('does not join tables of other columns, or a table that ends on its page', () => {
    expect(joinedTables([[table([head, ['A', '1', '100']])], [table([['x', 'y']], 60, [50, 200, 350])]])).toEqual([])
    // Another table after it on the first page: the item list ended there.
    expect(
      joinedTables([[table([head, ['A', '1', '100']]), table([['비고', '', '']], 400)], [table([['C', '3', '300']], 60)]]),
    ).toHaveLength(1)
    expect(joinedTables([[table([head])]])).toEqual([])
  })

  it('takes pages scanned a little shifted or at a slightly different size as the same table', () => {
    const first = table([head, ['A', '1', '100']])
    // The next page 9 points to the right and 2 % larger: no border is where it was.
    const shifted = table([['B', '2', '200']], 60, [59, 161, 263, 365])
    expect(continues(first, shifted)).toBe(true)
    expect(joinedTables([[first], [shifted]])).toHaveLength(1)
    // Other proportions, or a much narrower table, is another table.
    expect(continues(first, table([['B', '2', '200']], 60, [59, 120, 263, 365]))).toBe(false)
    expect(continues(first, table([['B', '2', '200']], 60, [50, 110, 170, 230]))).toBe(false)
  })
})
