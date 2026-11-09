import type { Table, TableCell } from './tableGrid'
import { rows } from './textSelect'

/** A difference between two versions of a document's table: an item's value changed, or an item added or taken out. */
export interface TableChange {
  kind: 'changed' | 'added' | 'removed'
  /** The item (its name, as the row's key column says). */
  item: string
  /** The column whose value changed (its heading); empty for an added or removed item. */
  column: string
  before: string
  after: string
}

/** Headings of the column that names a row: 품명, 품목, 항목, 내용, 규격 ... (not 번호). */
const NAME_HEADING = /^(품\s*명|품\s*목|항\s*목|내\s*용|제\s*품\s*명|모\s*델|명\s*칭|item|description|name|product)/i
const NUMBER_HEADING = /^(no\.?|번\s*호|순\s*번|순\s*서|#)$/i
const AMOUNT = /^[-+−△▲]?\s*[\d,]+(\.\d+)?\s*(원|-원)?$/

const textOf = (cell: TableCell | undefined) =>
  cell ? rows(cell.texts).map((row) => row.map((t) => t.text.trim()).join(' ')).join(' ').trim() : ''
const norm = (s: string) => s.replace(/\s+/g, '')

/** A table read as rows of named values: the heading row's labels, and for each row below it its key and values. */
interface ItemTable {
  columns: string[]
  keyColumn: number
  items: { key: string; label: string; values: Map<string, string> }[]
}

/** The table read as items, or null when it has no heading row with a column naming its rows. */
export function itemsOf(table: Table): ItemTable | null {
  const rowCount = table.ys.length - 1
  const at = (r: number, c: number) => table.cells.find((x) => x.row === r && x.col <= c && c < x.col + x.colSpan)
  for (let heading = 0; heading < Math.min(rowCount, 5); heading++) {
    const columns: string[] = []
    for (let c = 0; c < table.xs.length - 1; c++) {
      const cell = at(heading, c)
      columns.push(cell && cell.col === c ? norm(textOf(cell)) : '')
    }
    const keyColumn = columns.findIndex((label) => NAME_HEADING.test(label))
    if (keyColumn < 0 || columns.filter((l) => l !== '').length < 2) continue
    const items: ItemTable['items'] = []
    const seen = new Map<string, number>()
    for (let r = heading + 1; r < rowCount; r++) {
      const label = textOf(at(r, keyColumn))
      const values = new Map<string, string>()
      columns.forEach((column, c) => {
        if (!column || c === keyColumn || NUMBER_HEADING.test(column)) return
        const cell = at(r, c)
        if (cell && cell.col === c) values.set(column, textOf(cell))
      })
      // A row without a name: its first text names it (a 합계 row whose label spans the first columns).
      const name = label || textOf(table.cells.filter((x) => x.row === r).sort((x, y) => x.col - y.col)[0])
      if (!name || [...values.values()].every((v) => v === '') && !label) continue
      const base = norm(name)
      const n = (seen.get(base) ?? 0) + 1
      seen.set(base, n)
      items.push({ key: n > 1 ? `${base}#${n}` : base, label: name, values })
    }
    if (items.length > 0) return { columns, keyColumn, items }
  }
  return null
}

/**
 * What changed between two versions of a document's item tables (a quote's first and second offer): the items
 * matched by their names, each value compared under its column's heading; items only in the new one are added,
 * only in the old one removed. Amounts compare as numbers ("220,300원" is "220,300").
 */
export function compareTables(before: Table[], after: Table[]): { changes: TableChange[]; compared: boolean } {
  const a = before.map(itemsOf).filter((t): t is ItemTable => t !== null)
  const b = after.map(itemsOf).filter((t): t is ItemTable => t !== null)
  if (a.length === 0 || b.length === 0) return { changes: [], compared: false }
  const itemsA = new Map(a.flatMap((t) => t.items.map((i) => [i.key, i] as const)))
  const itemsB = new Map(b.flatMap((t) => t.items.map((i) => [i.key, i] as const)))
  const same = (x: string, y: string) => {
    if (AMOUNT.test(x) && AMOUNT.test(y)) return x.replace(/[^\d.\-−]/g, '') === y.replace(/[^\d.\-−]/g, '')
    return norm(x) === norm(y)
  }
  const changes: TableChange[] = []
  for (const [key, item] of itemsA) {
    const other = itemsB.get(key)
    if (!other) {
      changes.push({ kind: 'removed', item: item.label, column: '', before: [...item.values.values()].filter(Boolean).join(' · '), after: '' })
      continue
    }
    for (const [column, value] of item.values) {
      const next = other.values.get(column)
      if (next !== undefined && !same(value, next)) changes.push({ kind: 'changed', item: item.label, column, before: value, after: next })
    }
  }
  for (const [key, item] of itemsB) {
    if (!itemsA.has(key)) {
      changes.push({ kind: 'added', item: item.label, column: '', before: '', after: [...item.values.values()].filter(Boolean).join(' · ') })
    }
  }
  return { changes, compared: true }
}
