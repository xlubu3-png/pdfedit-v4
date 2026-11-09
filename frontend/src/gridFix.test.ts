import { describe, expect, it } from 'vitest'
import { splitArea } from './borderless'
import { gridOf, gridTable, layout, notCovered, toTsv, withGrids } from './tableGrid'
import type { Table } from './tableGrid'
import type { PlacedText } from './textSelect'

const t = (text: string, left: number, top: number): PlacedText => ({ text, left, top, height: 10, width: text.length * 5 })

describe('tables set out by hand', () => {
  const found: Table = gridTable({ xs: [0, 100, 200], ys: [0, 20, 40] })
  const elsewhere: Table = gridTable({ xs: [0, 100], ys: [300, 320] })

  it('take the place of the tables found where they lie, and leave the others', () => {
    const grid = { xs: [0, 60, 130, 200], ys: [0, 20, 40] }
    const used = withGrids([found, elsewhere], [grid])
    expect(used).toHaveLength(2)
    expect(used[0].xs).toEqual([0, 60, 130, 200])
    expect(used[1]).toBe(elsewhere)
    expect(notCovered([found, elsewhere], [grid])).toEqual([elsewhere])
    expect(withGrids([found], [])).toEqual([found])
  })

  it('put the text in their cells', () => {
    const texts = [t('Name', 5, 5), t('Kim', 70, 5), t('Phone', 5, 25), t('010', 70, 25)]
    const blocks = layout(texts, withGrids([], [{ xs: [0, 60, 200], ys: [0, 20, 40] }]), false)
    expect(toTsv(blocks).split('\n')).toEqual(['Name\tKim', 'Phone\t010'])
  })

  it('a table drawn over text is split into its lines and the gaps between its columns', () => {
    const texts = [t('Name', 5, 5), t('Kim', 70, 5), t('Phone', 5, 25), t('010-1234', 70, 25), t('outside', 5, 200)]
    const grid = splitArea(texts, 0, 0, 200, 40)
    expect(grid.ys).toHaveLength(3)
    expect(grid.ys[0]).toBe(0)
    expect(grid.ys[2]).toBe(40)
    expect(grid.xs).toHaveLength(3)
    expect(grid.xs[1]).toBeGreaterThan(30)
    expect(grid.xs[1]).toBeLessThan(70)
  })

  it('an area with no text in it is one cell', () => {
    expect(splitArea([t('far', 500, 500)], 0, 0, 100, 50)).toEqual({ xs: [0, 100], ys: [0, 50] })
  })
})

describe('cells merged by hand', () => {
  const grid = { xs: [0, 100, 200, 300], ys: [0, 20, 40], merges: [{ x0: 0, y0: 0, x1: 200, y1: 20 }] }

  it('become one cell over the columns and rows they take', () => {
    const t = gridTable(grid)
    const first = t.cells.find((c) => c.row === 0 && c.col === 0)
    expect(first).toMatchObject({ colSpan: 2, rowSpan: 1 })
    expect(t.cells.filter((c) => c.row === 0)).toHaveLength(2)
    expect(t.cells).toHaveLength(5)
  })

  it('come back from the table as the same merges', () => {
    expect(gridOf(gridTable(grid))).toEqual(grid)
  })

  it('snap to the nearest borders and are left out when they take one cell only', () => {
    const t = gridTable({ ...grid, merges: [{ x0: 2, y0: 1, x1: 98, y1: 39 }, { x0: 101, y0: 0, x1: 199, y1: 20 }] })
    expect(t.cells.find((c) => c.row === 0 && c.col === 0)).toMatchObject({ rowSpan: 2, colSpan: 1 })
    expect(t.cells.find((c) => c.row === 0 && c.col === 1)).toMatchObject({ rowSpan: 1, colSpan: 1 })
  })

  it('go into the sheet as one merged cell', () => {
    const texts = [t('합계', 10, 5), t('9', 210, 5)]
    expect(toTsv(layout(texts, withGrids([], [grid]), false)).split('\n')[0]).toBe('합계\t\t9')
  })
})
