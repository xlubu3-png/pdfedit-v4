import { describe, expect, it } from 'vitest'
import { splitArea } from './borderless'
import { gridTable, layout, notCovered, toTsv, withGrids } from './tableGrid'
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
