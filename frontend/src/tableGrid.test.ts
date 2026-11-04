import { describe, expect, it } from 'vitest'
import { findTables, hasTable, layout, toHtml, toTsv } from './tableGrid'
import type { Rulings } from './tableGrid'
import type { PlacedText } from './textSelect'

/** A grid of borders: columns at xs, rows at ys, every line drawn across the whole table. */
function grid(xs: number[], ys: number[]): Rulings {
  return {
    horizontal: ys.map((y) => ({ y, x1: xs[0], x2: xs[xs.length - 1] })),
    vertical: xs.map((x) => ({ x, y1: ys[0], y2: ys[ys.length - 1] })),
  }
}

/** Text of the given width placed in the middle of the cell [x0,x1] x [y0,y1]. */
const at = (text: string, x0: number, x1: number, y0: number, y1: number, width = 10): PlacedText => ({
  text,
  left: (x0 + x1) / 2 - width / 2,
  top: (y0 + y1) / 2 - 5,
  height: 10,
  width,
})

describe('findTables and layout', () => {
  // A quote: a header row, two item rows (the second with an empty cell), and a total row whose first three cells
  // are merged (no borders between them), as Excel draws it.
  const xs = [0, 30, 90, 150, 220]
  const ys = [0, 20, 40, 60, 80]
  const rulings = grid(xs, ys)
  rulings.vertical = [
    { x: 0, y1: 0, y2: 80 },
    { x: 30, y1: 0, y2: 60 },
    { x: 90, y1: 0, y2: 60 },
    { x: 150, y1: 0, y2: 80 },
    { x: 220, y1: 0, y2: 80 },
  ]
  const texts = [
    at('NO', 0, 30, 0, 20),
    at('품명', 30, 90, 0, 20),
    at('수량', 90, 150, 0, 20),
    at('금액', 150, 220, 0, 20),
    at('1', 0, 30, 20, 40),
    at('1-A', 30, 90, 20, 40),
    at('5', 90, 150, 20, 40),
    { ...at('220,300', 150, 220, 20, 40, 40), left: 175 }, // right-aligned
    at('2', 0, 30, 40, 60),
    at('1-B', 30, 90, 40, 60),
    // no amount on this row
    at('1', 90, 150, 40, 60),
    at('합계', 0, 150, 60, 80),
    { ...at('220,300', 150, 220, 60, 80, 40), left: 175 },
  ]

  it('finds the grid and merges cells no border parts', () => {
    const [table] = findTables(rulings)

    expect(table.xs).toEqual(xs)
    expect(table.ys).toEqual(ys)
    const total = table.cells.find((c) => c.row === 3 && c.col === 0)!
    expect(total.colSpan).toBe(3)
    expect(table.cells).toHaveLength(4 * 3 + 2)
  })

  it('puts every piece in its own column, empty cells kept, as tab-separated text', () => {
    const blocks = layout(texts, findTables(rulings), false)

    expect(hasTable(blocks)).toBe(true)
    expect(toTsv(blocks).split('\n')).toEqual(['NO\t품명\t수량\t금액', '1\t1-A\t5\t220,300', '2\t1-B\t1', '합계\t\t\t220,300'])
  })

  it('gives Excel HTML with the merged cell, borders, widths and text kept as text', () => {
    const html = toHtml(layout(texts, findTables(rulings), false))

    expect(html).toContain('<td colspan="3"')
    expect(html).toContain('<col width="40">') // 30 points
    expect(html).toContain('border-top:.5pt solid #000')
    // "1-A" would turn into a date without this; a number stays a number.
    expect(html).toMatch(/mso-number-format:'\\@'[^>]*>1-A</)
    expect(html).not.toMatch(/mso-number-format[^>]*>220,300</)
    expect(html).toMatch(/text-align:right[^>]*>220,300</)
  })

  it('cuts a table down to the cells the dragged-over text is in', () => {
    const picked = texts.filter((t) => ['1-A', '5', '1-B'].includes(t.text))
    const blocks = layout(picked, findTables(rulings), true)

    expect(toTsv(blocks).split('\n')).toEqual(['1-A\t5', '1-B'])
  })

  it('keeps the lines of a cell in the cell, and text outside any table as rows', () => {
    const two = [at('부산시', 30, 90, 20, 30), at('강서구', 30, 90, 30, 40), at('NO', 0, 30, 0, 20), at('제목', 300, 340, -40, -30)]
    const blocks = layout(two, findTables(rulings), true)

    expect(toTsv(blocks).split('\n')[0]).toBe('제목')
    expect(toTsv(blocks)).toContain('"부산시\n강서구"')
    expect(toHtml(blocks)).toContain('부산시<br style="mso-data-placement:same-cell">강서구')
  })

  it('takes no table from a lone underline, and none when no text is in a table', () => {
    expect(findTables({ horizontal: [{ y: 10, x1: 0, x2: 100 }], vertical: [] })).toEqual([])
    expect(hasTable(layout([at('제목', 300, 340, 100, 120)], findTables(rulings), false))).toBe(false)
  })

  it('joins lines drawn in pieces and keeps tables that do not touch apart', () => {
    const pieces: Rulings = {
      horizontal: [
        { y: 0, x1: 0, x2: 50 },
        { y: 0.5, x1: 50, x2: 100 },
        { y: 20, x1: 0, x2: 100 },
        { y: 200, x1: 0, x2: 100 },
        { y: 220, x1: 0, x2: 100 },
      ],
      vertical: [
        { x: 0, y1: 0, y2: 20 },
        { x: 100, y1: 0, y2: 20 },
        { x: 0, y1: 200, y2: 220 },
        { x: 100, y1: 200, y2: 220 },
      ],
    }
    const tables = findTables(pieces)

    expect(tables).toHaveLength(2)
    expect(tables[0].cells).toHaveLength(1)
  })
})

describe('alignment', () => {
  it('sees a number padded with spaces as right-aligned', () => {
    const rulings = grid([0, 100], [0, 20])
    const padded: PlacedText = { text: '          44,060', left: 30, top: 5, height: 10, width: 66 }
    const html = toHtml(layout([padded], findTables(rulings), false))

    expect(html).toMatch(/text-align:right[^>]*>44,060</)
  })
})
