import { describe, expect, it } from 'vitest'
import { borderlessTables } from './borderless'
import { hasTable, layout, toTsv } from './tableGrid'
import type { PlacedText } from './textSelect'

/** A piece of text 10 points tall, about 5 points a letter. */
const t = (text: string, left: number, top: number, width = text.length * 5): PlacedText => ({
  text,
  left,
  top,
  height: 10,
  width,
})

describe('borderlessTables', () => {
  // A parts list with no lines: names left aligned, numbers right aligned, a heading above and a note below.
  const page = [
    t('Parts list for the air shower unit', 0, 0),
    t('Part', 0, 20),
    t('Qty', 120, 20),
    t('Amount', 170, 20),
    t('Fan motor', 0, 34),
    t('2', 130, 34),
    t('220,300', 175, 34),
    t('HEPA filter', 0, 48),
    t('12', 125, 48),
    t('19,770', 180, 48),
    t('Door switch', 0, 62),
    t('1', 130, 62),
    t('8,000', 185, 62),
    t('All prices exclude delivery and are valid for thirty days from today.', 0, 90),
  ]

  it('finds the columns from where the text lines up, and leaves the heading and the note out', () => {
    const tables = borderlessTables(page)
    expect(tables).toHaveLength(1)
    const [table] = tables
    expect(table.borderless).toBe(true)
    expect(table.xs).toHaveLength(4)
    expect(table.ys).toHaveLength(5)
    expect(table.ys[0]).toBe(20)
  })

  it('puts each piece in its cell when laid out for a spreadsheet', () => {
    const blocks = layout(page, [], false)
    expect(hasTable(blocks)).toBe(true)
    const tsv = toTsv(blocks).split('\n')
    expect(tsv).toContain('Part\tQty\tAmount')
    expect(tsv).toContain('HEPA filter\t12\t19,770')
    expect(tsv[0]).toBe('Parts list for the air shower unit')
  })

  it('does not take running text for a table', () => {
    const prose = [0, 14, 28, 42].map((top, i) =>
      t(['The fan is checked every month by', 'the maintenance team, and the filter', 'is replaced when its pressure drop', 'reaches the limit on its label.'][i], 0, top),
    )
    expect(borderlessTables(prose)).toEqual([])
  })

  it('does not take words a few spaces apart for columns', () => {
    // Words as separate pieces, a space apart, at different places on each line.
    const words = [
      [t('Check', 0, 0), t('the', 30, 0), t('door', 50, 0), t('seal', 75, 0)],
      [t('and', 0, 14), t('clean', 20, 14), t('the', 48, 14), t('nozzles', 68, 14)],
      [t('every', 0, 28), t('week', 28, 28), t('without', 53, 28), t('fail', 90, 28)],
    ].flat()
    expect(borderlessTables(words)).toEqual([])
  })

  it('cuts a line a program padded into columns with spaces', () => {
    const padded = (text: string, top: number): PlacedText => ({
      ...t(text, 0, top, text.length * 5),
      chars: [...text].map((_, i) => i * 5),
    })
    const lines = [padded('Name        Qty', 0), padded('Bolt        4', 14), padded('Nut         12', 28)]
    const blocks = layout(lines, [], false)
    expect(toTsv(blocks).split('\n')).toEqual(['Name\tQty', 'Bolt\t4', 'Nut\t12'])
  })

  it('needs three rows', () => {
    expect(borderlessTables([t('Name', 0, 0), t('Kim', 100, 0), t('Phone', 0, 14), t('010', 100, 14)])).toEqual([])
  })
})
