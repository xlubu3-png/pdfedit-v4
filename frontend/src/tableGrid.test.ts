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

describe('a border round the page', () => {
  it('is a frame, not a table: the tables inside it keep their own cells', () => {
    const inner = grid([50, 100, 200], [100, 120, 140])
    const frame = grid([10, 500], [10, 800])
    const rulings = {
      horizontal: [...frame.horizontal, ...inner.horizontal],
      vertical: [...frame.vertical, ...inner.vertical],
    }
    const tables = findTables(rulings)
    const texts = [at('금액', 50, 100, 100, 120), at('5,300,000', 100, 200, 100, 120), at('견적서', 200, 300, 30, 50)]

    expect(tables).toHaveLength(1)
    expect(toTsv(layout(texts, tables, false)).split('\n')).toEqual(['견적서', '금액\t5,300,000', ''])
  })
})


describe('text over a column border', () => {
  it('is cut into the cells its letters are in', () => {
    const rulings = grid([0, 100, 130, 160], [0, 20, 40])
    // "HEPA 75T  1  EA" written as one piece over three columns, each letter 5 points wide from x 10.
    const text = 'HEPA 75T            1     EA'
    const chars = [...text].map((_, i) => 10 + i * 5)
    const run: PlacedText = { text, left: 10, top: 25, height: 10, width: text.length * 5, chars }
    const header = [at('규격', 0, 100, 0, 20), at('수량', 100, 130, 0, 20), at('단위', 130, 160, 0, 20)]

    expect(toTsv(layout([...header, run], findTables(rulings), false)).split('\n')).toEqual([
      '규격\t수량\t단위',
      'HEPA 75T\t1\tEA',
    ])
  })

  it('stays whole when its letters are all in one cell or their places are unknown', () => {
    const rulings = grid([0, 100, 130], [0, 20])
    const one: PlacedText = { text: 'AB', left: 10, top: 5, height: 10, width: 10, chars: [10, 15] }
    const unknown: PlacedText = { text: 'C D', left: 105, top: 5, height: 10, width: 15 }

    expect(toTsv(layout([one, unknown], findTables(rulings), false))).toBe('AB\tC D')
  })
})

describe('a box with a gap where another table sits', () => {
  it('is cut into the rows above and below the gap, and the table inside keeps its cells', () => {
    // An items table (rows 0-40), a gap with no lines (40-200) holding a detail table, and a total row (200-220),
    // all one box: its side lines run from top to bottom.
    const outer: Rulings = {
      horizontal: [0, 20, 40, 200, 220].map((y) => ({ y, x1: 0, x2: 300 })),
      vertical: [
        { x: 0, y1: 0, y2: 220 },
        { x: 300, y1: 0, y2: 220 },
        { x: 100, y1: 0, y2: 40 },
      ],
    }
    const inner = grid([50, 150, 250], [80, 100, 120])
    const tables = findTables({
      horizontal: [...outer.horizontal, ...inner.horizontal],
      vertical: [...outer.vertical, ...inner.vertical],
    })
    const texts = [
      at('품명', 0, 100, 0, 20),
      at('금액', 100, 300, 0, 20),
      at('AIR', 0, 100, 20, 40),
      at('4,700,000', 100, 300, 20, 40),
      at('세부사양', 0, 100, 50, 70),
      at('Fan', 50, 150, 80, 100),
      at('1', 150, 250, 80, 100),
      at('계', 0, 300, 200, 220),
    ]

    expect(toTsv(layout(texts, tables, false)).split('\n')).toEqual([
      '품명\t금액',
      'AIR\t4,700,000',
      '세부사양',
      'Fan\t1',
      '',
      '계',
    ])
  })
})

