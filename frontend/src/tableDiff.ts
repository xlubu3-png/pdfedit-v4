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

interface Item {
  key: string
  label: string
  values: Map<string, string>
}

/** A table read as rows of named values: the heading row's labels, and for each row below it its key and values. */
interface ItemTable {
  columns: string[]
  keyColumn: number
  /** Each row: its name without spaces (several rows may have the same one), the name as written, its values. */
  items: Item[]
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
      items.push({ key: norm(name), label: name, values })
    }
    if (items.length > 0) return { columns, keyColumn, items }
  }
  return null
}

const same = (x: string, y: string) => {
  if (AMOUNT.test(x) && AMOUNT.test(y)) return x.replace(/[^\d.\-−]/g, '') === y.replace(/[^\d.\-−]/g, '')
  return norm(x) === norm(y)
}

/** How many of the values two rows have under the same headings are the same. */
function alike(a: Item, b: Item): number {
  let n = 0
  for (const [column, value] of a.values) {
    const other = b.values.get(column)
    if (other !== undefined && value !== '' && same(value, other)) n++
  }
  return n
}

/**
 * The rows of one name in the old and the new version paired off: the two most alike first (the same size, the
 * same unit price ...), rows as alike as each other in their order. What is left over on either side has no pair.
 */
function pairs(before: Item[], after: Item[]): { pairs: [Item, Item][]; removed: Item[]; added: Item[] } {
  const candidates: { i: number; j: number; score: number }[] = []
  before.forEach((a, i) => after.forEach((b, j) => candidates.push({ i, j, score: alike(a, b) })))
  candidates.sort((x, y) => y.score - x.score || Math.abs(x.i - x.j) - Math.abs(y.i - y.j) || x.i - y.i)
  const usedA = new Set<number>()
  const usedB = new Set<number>()
  const found: { i: number; j: number }[] = []
  for (const c of candidates) {
    if (usedA.has(c.i) || usedB.has(c.j)) continue
    usedA.add(c.i)
    usedB.add(c.j)
    found.push(c)
  }
  found.sort((x, y) => x.i - y.i)
  return {
    pairs: found.map((f) => [before[f.i], after[f.j]]),
    removed: before.filter((_, i) => !usedA.has(i)),
    added: after.filter((_, j) => !usedB.has(j)),
  }
}

/**
 * What changed between two versions of a document's item tables (a quote's first and second offer): the items
 * matched by their names, each value compared under its column's heading; items only in the new one are added,
 * only in the old one removed. Amounts compare as numbers ("220,300원" is "220,300"). An item named several times
 * (the same part in two sizes, or in two tables) is matched with the row most like it, and told apart in the
 * result by its place: "앵글 (2)".
 */
export function compareTables(before: Table[], after: Table[]): { changes: TableChange[]; compared: boolean } {
  const a = before.map(itemsOf).filter((t): t is ItemTable => t !== null)
  const b = after.map(itemsOf).filter((t): t is ItemTable => t !== null)
  if (a.length === 0 || b.length === 0) return { changes: [], compared: false }
  const byName = (tables: ItemTable[]) => {
    const groups = new Map<string, Item[]>()
    for (const item of tables.flatMap((t) => t.items)) groups.set(item.key, [...(groups.get(item.key) ?? []), item])
    return groups
  }
  const groupsA = byName(a)
  const groupsB = byName(b)
  /** The name as shown: with its place among the rows of that name when there are several. */
  const shown = (item: Item, group: Item[]) => (group.length > 1 ? `${item.label} (${group.indexOf(item) + 1})` : item.label)
  const valuesOf = (item: Item) => [...item.values.values()].filter(Boolean).join(' · ')
  const changes: TableChange[] = []
  const added: TableChange[] = []
  for (const [key, group] of groupsA) {
    const others = groupsB.get(key) ?? []
    const matched = pairs(group, others)
    for (const [item, other] of matched.pairs) {
      for (const [column, value] of item.values) {
        const next = other.values.get(column)
        if (next !== undefined && !same(value, next)) {
          changes.push({ kind: 'changed', item: shown(item, group), column, before: value, after: next })
        }
      }
    }
    for (const item of matched.removed) {
      changes.push({ kind: 'removed', item: shown(item, group), column: '', before: valuesOf(item), after: '' })
    }
    for (const item of matched.added) {
      added.push({ kind: 'added', item: shown(item, others), column: '', before: '', after: valuesOf(item) })
    }
  }
  for (const [key, group] of groupsB) {
    if (groupsA.has(key)) continue
    for (const item of group) added.push({ kind: 'added', item: shown(item, group), column: '', before: '', after: valuesOf(item) })
  }
  return { changes: [...changes, ...added], compared: true }
}
