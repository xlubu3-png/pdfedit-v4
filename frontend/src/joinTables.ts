import type { Table, TableCell } from './tableGrid'

/** Column borders this close (points) are the same column on the next page. */
const SAME_COLUMN = 4

/** A table running on over pages, joined into one: which pages it took, and the table. */
export interface JoinedTable {
  /** The places of the pages it runs over, in order (indexes into the pages given). */
  pages: number[]
  table: Table
}

const rowText = (table: Table, row: number) =>
  table.cells
    .filter((c) => c.row === row)
    .sort((a, b) => a.col - b.col)
    .map((c) => c.texts.map((t) => t.text.trim()).join(' ').replace(/\s+/g, ''))
    .join('|')

/** Whether `next` goes on with `table`: the same columns, at the same places across the page. */
export function continues(table: Table, next: Table): boolean {
  if (table.xs.length !== next.xs.length || table.xs.length < 3) return false
  return table.xs.every((x, i) => Math.abs(x - next.xs[i]) <= SAME_COLUMN)
}

/**
 * The tables that run on from one page to the next - a quote's item list over two or three pages - joined into
 * one each: the last table of a page and the first of the next page are one when their columns are the same, and
 * the next page's first row is left out when it repeats the heading row. A table on one page only is not given.
 *
 * @param pages the tables of each page, top to bottom, their text in their cells
 */
export function joinedTables(pages: Table[][]): JoinedTable[] {
  const out: JoinedTable[] = []
  let chain: { pages: number[]; parts: Table[] } | null = null
  for (let p = 0; p < pages.length; p++) {
    const tables = pages[p]
    if (tables.length === 0) {
      if (chain) out.push(finish(chain))
      chain = null
      continue
    }
    if (chain && continues(chain.parts[chain.parts.length - 1], tables[0])) {
      chain.pages.push(p)
      chain.parts.push(tables[0])
      // The table ends on this page when another one follows it here.
      if (tables.length === 1) continue
    }
    if (chain) out.push(finish(chain))
    // This page's last table may go on to the next page.
    chain = { pages: [p], parts: [tables[tables.length - 1]] }
  }
  if (chain) out.push(finish(chain))
  return out.filter((j) => j.pages.length >= 2)
}

/** The parts stacked into one table: their rows one after another, a repeated heading row left out. */
function finish(chain: { pages: number[]; parts: Table[] }): JoinedTable {
  const [first, ...rest] = chain.parts
  const heading = rowText(first, 0)
  const xs = [...first.xs]
  const ys = [...first.ys]
  const cells: TableCell[] = first.cells.map((c) => ({ ...c }))
  for (const part of rest) {
    const skip = rowText(part, 0) === heading && heading !== '' ? 1 : 0
    const rowBase = ys.length - 1 - skip
    const yBase = ys[ys.length - 1] - part.ys[skip]
    for (let r = skip + 1; r < part.ys.length; r++) ys.push(part.ys[r] + yBase)
    for (const cell of part.cells) {
      if (cell.row < skip) continue
      cells.push({
        ...cell,
        row: cell.row + rowBase,
        top: cell.top + yBase,
        bottom: cell.bottom + yBase,
        left: xs[cell.col],
        right: xs[cell.col + cell.colSpan],
        texts: cell.texts.map((t) => ({ ...t, top: t.top + yBase })),
      })
    }
  }
  return { pages: chain.pages, table: { xs, ys, cells } }
}
