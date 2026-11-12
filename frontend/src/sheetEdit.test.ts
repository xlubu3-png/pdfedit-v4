import { describe, expect, it } from 'vitest'
import { fromBook, toBook } from './sheetEdit'
import type { Sheet } from './tableGrid'

const all = { top: true, right: true, bottom: true, left: true }
const sheet: Sheet = {
  widths: [40, 120, 60],
  heights: [30, 20, 20],
  cells: [
    { row: 0, col: 0, rowSpan: 1, colSpan: 3, lines: ['견 적 서'], borders: null, align: 'center', size: 16 },
    { row: 1, col: 0, rowSpan: 1, colSpan: 1, lines: ['1'], borders: all, align: 'right', size: 10 },
    { row: 1, col: 1, rowSpan: 1, colSpan: 1, lines: ['환기 팬', '모터'], borders: all, align: 'left', size: 10 },
    { row: 1, col: 2, rowSpan: 1, colSpan: 1, lines: ['220,300'], borders: all, align: 'right', size: 10 },
    { row: 2, col: 0, rowSpan: 1, colSpan: 2, lines: ['합계'], borders: all, align: 'center', size: 10 },
    { row: 2, col: 2, rowSpan: 1, colSpan: 1, lines: ['220,300'], borders: all, align: 'right', size: 10 },
  ],
}

describe('the sheets handed to 표 다듬기 and taken back', () => {
  it('come back as they went when nothing was changed', () => {
    expect(fromBook(toBook([{ name: '1쪽', sheet }], '견적서'))).toEqual([{ name: '1쪽', sheet }])
  })

  it('go over with numbers as numbers, merges, borders all round a merged cell, and widths in pixels', () => {
    const s = toBook([{ name: '1쪽', sheet }], '견적서').sheets.s1
    expect(s.cellData[1][2].v).toBe(220300)
    expect((s.cellData[1][2].s as { n: { pattern: string } }).n.pattern).toBe('#,##0')
    expect(s.cellData[1][1].v).toBe('환기 팬\n모터')
    expect(s.mergeData).toEqual([
      { startRow: 0, endRow: 0, startColumn: 0, endColumn: 2 },
      { startRow: 2, endRow: 2, startColumn: 0, endColumn: 1 },
    ])
    // The merged 합계: its left cell draws the left side, its right cell the right side.
    expect(Object.keys((s.cellData[2][0].s as { bd: object }).bd).sort()).toEqual(['b', 'l', 't'])
    expect(Object.keys((s.cellData[2][1].s as { bd: object }).bd).sort()).toEqual(['b', 'r', 't'])
    expect(s.columnData[1].w).toBe(160)
    expect(s.rowCount).toBeGreaterThan(3)
  })

  it('bring back what was done on the screen: a formula with its result, bold, a background, a new row', () => {
    const book = toBook([{ name: '1쪽', sheet }], '견적서')
    const s = book.sheets.s1
    s.cellData[2][2] = { v: 440600, f: '=SUM(C2:C2)*2', s: { ...(s.cellData[2][2].s as object), bl: 1, bg: { rgb: '#FFF2CC' } } }
    book.styles.note = { fs: 9 }
    s.cellData[3] = { 0: { v: '부가세 별도', s: 'note' } }
    const back = fromBook(book)[0].sheet
    expect(back.cells.find((c) => c.row === 2 && c.col === 2)).toMatchObject({
      lines: ['440,600'],
      formula: 'SUM(C2:C2)*2',
      bold: true,
      fill: 'FFF2CC',
    })
    expect(back.cells.find((c) => c.row === 3)).toMatchObject({ lines: ['부가세 별도'], size: 9, borders: null })
    expect(back.heights).toHaveLength(4)
  })
})
