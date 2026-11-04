import { rows } from './textSelect'
import type { PlacedText } from './textSelect'

/** The straight lines drawn on a page (a table's borders), in points from its top-left corner. */
export interface Rulings {
  horizontal: { y: number; x1: number; x2: number }[]
  vertical: { x: number; y1: number; y2: number }[]
}

/** A cell of a table, merged cells as one: where it is in the grid and on the page, and its text. */
export interface TableCell {
  row: number
  col: number
  rowSpan: number
  colSpan: number
  left: number
  top: number
  right: number
  bottom: number
  /** Borders drawn on its sides. */
  borders: { top: boolean; right: boolean; bottom: boolean; left: boolean }
  texts: PlacedText[]
  /** How its text sits, when known from how the area was split (else worked out from where the text is). */
  align?: 'left' | 'center' | 'right'
  /** A cell with another table drawn inside it: its text belongs to that table or to no table, not to this cell. */
  container?: boolean
}

/** A table: the positions of its column and row borders (points), and its cells. */
export interface Table {
  xs: number[]
  ys: number[]
  cells: TableCell[]
}

/** A part of the copied text: a table, or a row of text outside any table. */
export type Block = { kind: 'table'; table: Table; top: number } | { kind: 'row'; texts: PlacedText[]; top: number }

/** Lines this close (points) are at one place; ends this close touch. */
const NEAR = 2

/** The tables the lines draw: lines that meet make one table, with at least two rows and columns of borders. */
export function findTables(rulings: Rulings): Table[] {
  const h = rulings.horizontal
  const v = rulings.vertical
  const n = h.length + v.length
  const parent = Array.from({ length: n }, (_, i) => i)
  const find = (i: number): number => (parent[i] === i ? i : (parent[i] = find(parent[i])))
  const join = (a: number, b: number) => {
    parent[find(a)] = find(b)
  }
  h.forEach((a, i) => {
    v.forEach((b, j) => {
      if (b.x >= a.x1 - NEAR && b.x <= a.x2 + NEAR && a.y >= b.y1 - NEAR && a.y <= b.y2 + NEAR) join(i, h.length + j)
    })
  })
  const groups = new Map<number, { h: Rulings['horizontal']; v: Rulings['vertical'] }>()
  const group = (i: number) => {
    const root = find(i)
    if (!groups.has(root)) groups.set(root, { h: [], v: [] })
    return groups.get(root)!
  }
  h.forEach((line, i) => group(i).h.push(line))
  v.forEach((line, j) => group(h.length + j).v.push(line))

  const tables: Table[] = []
  for (const lines of groups.values()) {
    const xs = clusters(lines.v.map((l) => l.x))
    const ys = clusters(lines.h.map((l) => l.y))
    if (xs.length < 2 || ys.length < 2) continue
    tables.push(buildTable(xs, ys, lines.h, lines.v))
  }
  return withoutFrames(tables).sort((a, b) => a.ys[0] - b.ys[0])
}

/**
 * Another table drawn inside a cell (a border round the whole sheet, or a box with a gap where a table sits, as
 * Excel prints them) would take all that table's text into the one cell. A cell as wide as its table is cut out:
 * the rows above and below it are tables of their own (a border round the sheet leaves nothing). A narrower one
 * is kept but takes no text.
 */
function withoutFrames(tables: Table[]): Table[] {
  const inside = (cell: TableCell, inner: Table) =>
    inner.xs[0] >= cell.left - NEAR &&
    inner.xs[inner.xs.length - 1] <= cell.right + NEAR &&
    inner.ys[0] >= cell.top - NEAR &&
    inner.ys[inner.ys.length - 1] <= cell.bottom + NEAR
  let current = tables
  for (let round = 0; round < 20; round++) {
    let changed = false
    const next: Table[] = []
    for (const table of current) {
      const others = current.filter((t) => t !== table)
      const frame = table.cells.find(
        (c) => !c.container && c.colSpan === table.xs.length - 1 && others.some((t) => inside(c, t)),
      )
      if (frame) {
        changed = true
        const above = cropTo(table, 0, frame.row, 0, table.xs.length - 1)
        const below = cropTo(table, frame.row + frame.rowSpan, table.ys.length - 1, 0, table.xs.length - 1)
        next.push(...[above, below].filter((t) => t.ys.length > 1 && t.cells.length > 0))
        continue
      }
      next.push({ ...table, cells: table.cells.map((c) => (others.some((t) => inside(c, t)) ? { ...c, container: true } : c)) })
    }
    current = next
    if (!changed) break
  }
  return current
}

/** The values, with those within {@link NEAR} of the one before taken as one (their middle). */
function clusters(values: number[]): number[] {
  const out: number[][] = []
  for (const value of [...values].sort((a, b) => a - b)) {
    const last = out[out.length - 1]
    if (last && value - last[last.length - 1] <= NEAR) last.push(value)
    else out.push([value])
  }
  return out.map((c) => c.reduce((s, x) => s + x, 0) / c.length)
}

function buildTable(xs: number[], ys: number[], h: Rulings['horizontal'], v: Rulings['vertical']): Table {
  const rowsCount = ys.length - 1
  const colsCount = xs.length - 1
  /** A vertical border at x passing the height y. */
  const hasV = (x: number, y: number) => v.some((l) => Math.abs(l.x - x) <= NEAR && y >= l.y1 - NEAR && y <= l.y2 + NEAR)
  /** A horizontal border at y passing the place x. */
  const hasH = (y: number, x: number) => h.some((l) => Math.abs(l.y - y) <= NEAR && x >= l.x1 - NEAR && x <= l.x2 + NEAR)
  const midX = (c: number) => (xs[c] + xs[c + 1]) / 2
  const midY = (r: number) => (ys[r] + ys[r + 1]) / 2

  const taken = Array.from({ length: rowsCount }, () => new Array<boolean>(colsCount).fill(false))
  const cells: TableCell[] = []
  for (let r = 0; r < rowsCount; r++) {
    for (let c = 0; c < colsCount; c++) {
      if (taken[r][c]) continue
      // Wider while no border parts it from the next column, then taller while no border parts it from the row below.
      let colSpan = 1
      while (c + colSpan < colsCount && !taken[r][c + colSpan] && !hasV(xs[c + colSpan], midY(r))) colSpan++
      let rowSpan = 1
      const open = (row: number) => {
        for (let k = c; k < c + colSpan; k++) {
          if (taken[row][k] || hasH(ys[row], midX(k))) return false
        }
        return true
      }
      while (r + rowSpan < rowsCount && open(r + rowSpan)) rowSpan++
      for (let i = r; i < r + rowSpan; i++) for (let k = c; k < c + colSpan; k++) taken[i][k] = true
      const right = c + colSpan
      const bottom = r + rowSpan
      cells.push({
        row: r,
        col: c,
        rowSpan,
        colSpan,
        left: xs[c],
        top: ys[r],
        right: xs[right],
        bottom: ys[bottom],
        borders: {
          top: hasH(ys[r], midX(c)),
          bottom: hasH(ys[bottom], midX(c)),
          left: hasV(xs[c], midY(r)),
          right: hasV(xs[right], midY(r)),
        },
        texts: [],
      })
    }
  }
  return { xs, ys, cells }
}

/**
 * The text laid out as it is on the page: the tables with each piece of text in the cell its middle is in, and the
 * rest as rows. With `onlyPicked`, a table keeps just the rows and columns the text is in (a part dragged over);
 * otherwise it is whole, empty cells and all. A table no text falls in is left out.
 */
export function layout(texts: PlacedText[], tables: Table[], onlyPicked: boolean): Block[] {
  const filled = tables.map((t) => ({ ...t, cells: t.cells.map((c) => ({ ...c, texts: [] as PlacedText[] })) }))
  const loose: PlacedText[] = []
  const cellAt = (x: number, y: number) =>
    filled.flatMap((t) => t.cells).find((c) => !c.container && x >= c.left && x <= c.right && y >= c.top && y <= c.bottom)
  for (const text of texts.flatMap((t) => splitAtBorders(t, cellAt))) {
    if (text.text.trim() === '') continue
    const x = text.left + (text.width ?? 0) / 2
    const y = text.top + text.height / 2
    const cell = cellAt(x, y)
    if (cell) cell.texts.push(text)
    else loose.push(text)
  }
  const blocks: Block[] = []
  for (const whole of filled) {
    if (!whole.cells.some((c) => c.texts.length > 0)) continue
    const table = splitAreas(whole)
    const used = table.cells.filter((c) => c.texts.length > 0)
    const kept = onlyPicked ? crop(table, used) : table
    blocks.push({ kind: 'table', table: kept, top: kept.ys[0] })
  }
  for (const row of rows(loose)) blocks.push({ kind: 'row', texts: row, top: row[0].top })
  return blocks.sort((a, b) => a.top - b.top)
}

/**
 * An area several columns wide with lines of text but no borders inside (as Excel draws a sheet's heading: the
 * date, the customer, the sender's address) is split back into the rows and columns the sheet had: a row for each
 * line of text, each piece in the column it starts in, a line centred in the area kept across it. The other cells
 * of the row grow over the new rows.
 */
function splitAreas(table: Table): Table {
  const splits = (c: TableCell) => c.rowSpan === 1 && c.colSpan > 1 && rows(c.texts).length > 1
  // For each row of the grid: the borders between its new rows, from the middles of its lines of text.
  const cuts: number[][] = table.ys.slice(1).map(() => [])
  for (let r = 0; r < cuts.length; r++) {
    const lines = table.cells.filter((c) => c.row === r && splits(c)).flatMap((c) => rows(c.texts))
    if (lines.length === 0) continue
    const middles = rows(lines.flat()).map((row) => {
      const tops = row.map((t) => t.top + t.height / 2)
      return tops.reduce((a, b) => a + b, 0) / tops.length
    })
    for (let i = 1; i < middles.length; i++) cuts[r].push((middles[i - 1] + middles[i]) / 2)
  }
  if (cuts.every((c) => c.length === 0)) return table

  // The new rows: where each old row starts among them.
  const ys: number[] = [table.ys[0]]
  const start: number[] = []
  table.ys.slice(1).forEach((y, r) => {
    start.push(ys.length - 1)
    ys.push(...cuts[r], y)
  })
  start.push(ys.length - 1)

  const cells: TableCell[] = []
  for (const cell of table.cells) {
    const row = start[cell.row]
    if (!splits(cell)) {
      cells.push({ ...cell, row, rowSpan: start[cell.row + cell.rowSpan] - row })
      continue
    }
    const count = start[cell.row + 1] - row
    for (let i = 0; i < count; i++) {
      const top = ys[row + i]
      const bottom = ys[row + i + 1]
      const pieces = cell.texts.filter((t) => {
        const middle = t.top + t.height / 2
        return middle >= top && (middle < bottom || i === count - 1)
      })
      const first = i === 0
      const last = i === count - 1
      const part = (col: number, colSpan: number, texts: PlacedText[], align?: TableCell['align']): TableCell => ({
        row: row + i,
        col,
        rowSpan: 1,
        colSpan,
        left: table.xs[col],
        top,
        right: table.xs[col + colSpan],
        bottom,
        borders: {
          top: first && cell.borders.top,
          bottom: last && cell.borders.bottom,
          left: col === cell.col && cell.borders.left,
          right: col + colSpan === cell.col + cell.colSpan && cell.borders.right,
        },
        texts,
        align,
      })
      const end = cell.col + cell.colSpan
      const middle = (cell.left + cell.right) / 2
      const lone = pieces.length === 1 ? pieces[0] : null
      const centred =
        lone && lone.width && Math.abs(lone.left + lone.width / 2 - middle) <= (cell.right - cell.left) * 0.05
      if (pieces.length === 0 || centred) {
        cells.push(part(cell.col, cell.colSpan, pieces, centred ? 'center' : undefined))
        continue
      }
      // Each piece from the column it starts in up to the next piece's column; pieces in one column go together.
      const columnOf = (t: PlacedText) => {
        let k = cell.col
        while (k + 1 < end && table.xs[k + 1] <= t.left + 1) k++
        return k
      }
      const byColumn = new Map<number, PlacedText[]>()
      for (const t of [...pieces].sort((a, b) => a.left - b.left)) {
        const k = columnOf(t)
        byColumn.set(k, [...(byColumn.get(k) ?? []), t])
      }
      const columns = [...byColumn.keys()].sort((a, b) => a - b)
      if (columns[0] > cell.col) cells.push(part(cell.col, columns[0] - cell.col, []))
      columns.forEach((k, j) => {
        const next = columns[j + 1] ?? end
        cells.push(part(k, next - k, byColumn.get(k)!, 'left'))
      })
    }
  }
  return { xs: table.xs, ys, cells }
}

/**
 * A piece of text that runs over a column border (a program wrote "규격 수량" or "75T  1" as one piece) cut into
 * the cells its letters are in. Text whose letters' places are unknown stays whole.
 */
function splitAtBorders(text: PlacedText, cellAt: (x: number, y: number) => TableCell | undefined): PlacedText[] {
  const chars = text.chars
  if (!chars || chars.length !== text.text.length) return [text]
  const y = text.top + text.height / 2
  const end = text.left + (text.width ?? 0)
  // Each letter's cell (a space goes with the letter before it); a letter outside any table is null.
  const cells: (TableCell | null)[] = []
  for (let i = 0; i < chars.length; i++) {
    const next = i + 1 < chars.length ? chars[i + 1] : end
    const middle = (chars[i] + Math.max(next, chars[i])) / 2
    cells.push(text.text[i].trim() === '' && i > 0 ? cells[i - 1] : (cellAt(middle, y) ?? null))
  }
  if (new Set(cells.filter((_, i) => text.text[i].trim() !== '')).size <= 1) return [text]
  const out: PlacedText[] = []
  let from = 0
  for (let i = 1; i <= chars.length; i++) {
    if (i < chars.length && cells[i] === cells[from]) continue
    const right = i < chars.length ? chars[i] : end
    out.push({
      text: text.text.slice(from, i),
      left: chars[from],
      top: text.top,
      height: text.height,
      width: Math.max(0, right - chars[from]),
      chars: chars.slice(from, i),
    })
    from = i
  }
  return out
}

/** The table cut down to the rows and columns the given cells take. */
function crop(table: Table, used: TableCell[]): Table {
  const r0 = Math.min(...used.map((c) => c.row))
  const r1 = Math.max(...used.map((c) => c.row + c.rowSpan))
  const c0 = Math.min(...used.map((c) => c.col))
  const c1 = Math.max(...used.map((c) => c.col + c.colSpan))
  return cropTo(table, r0, r1, c0, c1)
}

/** The table's rows r0 to r1 and columns c0 to c1 (ends not included), cells cut at the edges. */
function cropTo(table: Table, r0: number, r1: number, c0: number, c1: number): Table {
  if (r1 <= r0 || c1 <= c0) return { xs: table.xs.slice(c0, c0 + 1), ys: table.ys.slice(r0, r0 + 1), cells: [] }
  const cells = table.cells
    .filter((c) => c.row < r1 && c.row + c.rowSpan > r0 && c.col < c1 && c.col + c.colSpan > c0)
    .map((c) => {
      const row = Math.max(c.row, r0)
      const col = Math.max(c.col, c0)
      const rowEnd = Math.min(c.row + c.rowSpan, r1)
      const colEnd = Math.min(c.col + c.colSpan, c1)
      return {
        ...c,
        row: row - r0,
        col: col - c0,
        rowSpan: rowEnd - row,
        colSpan: colEnd - col,
        top: table.ys[row],
        bottom: table.ys[rowEnd],
        left: table.xs[col],
        right: table.xs[colEnd],
      }
    })
  return { xs: table.xs.slice(c0, c1 + 1), ys: table.ys.slice(r0, r1 + 1), cells }
}

/** Whether the text has a table in it to paste as one: some text in the cells of a table. */
export function hasTable(blocks: Block[]): boolean {
  return blocks.some((b) => b.kind === 'table' && b.table.cells.filter((c) => c.texts.length > 0).length >= 2)
}

/** A cell's text: its lines top to bottom, the pieces on a line left to right. */
function cellLines(cell: TableCell): string[] {
  return rows(cell.texts).map((row) => row.map((t) => t.text.trim()).join(' '))
}

/** A number as a spreadsheet reads it ("1,234", "-5.5"); anything else is kept as text so it is not made a date. */
const NUMBER = /^[-+]?(\d{1,3}(,\d{3})+|0|[1-9]\d*)(\.\d+)?$/

function cellAlign(cell: TableCell): 'left' | 'center' | 'right' {
  if (cell.align) return cell.align
  if (cell.texts.length === 0) return 'left'
  if (cell.texts.some((t) => !t.width)) return 'left'
  // Where the letters are, without the spaces a program pads a number with to push it right ("     44,060").
  const visible = (t: PlacedText) => {
    const lead = t.text.length - t.text.trimStart().length
    const trail = t.text.length - t.text.trimEnd().length
    const end = t.left + (t.width ?? 0)
    if (t.chars && t.chars.length === t.text.length && lead < t.text.length) {
      // The letters' own places: the last letter ends where the next character (a space) starts.
      const last = t.text.length - trail
      return { from: t.chars[lead], to: last < t.chars.length ? t.chars[last] : end }
    }
    const each = (t.width ?? 0) / Math.max(1, t.text.length)
    return { from: t.left + lead * each, to: end - trail * each }
  }
  const left = Math.min(...cell.texts.map((t) => visible(t).from)) - cell.left
  const right = cell.right - Math.max(...cell.texts.map((t) => visible(t).to))
  if (Math.abs(left - right) <= Math.max(3, (cell.right - cell.left) * 0.08)) return 'center'
  return left > right ? 'right' : 'left'
}

const escapeHtml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
/** Points to the pixels Excel counts widths in. */
const px = (pt: number) => Math.max(1, Math.round((pt * 96) / 72))
const BORDER = '.5pt solid #000'

/**
 * The blocks as HTML a spreadsheet pastes as cells (Excel, Google Sheets, Hangul): tables with their merged cells,
 * borders, column widths and row heights, text kept as text, lines inside a cell kept in the cell.
 */
export function toHtml(blocks: Block[]): string {
  const parts: string[] = []
  let loose: string[] = []
  const flush = () => {
    if (loose.length > 0) parts.push(`<table>${loose.join('')}</table>`)
    loose = []
  }
  for (const block of blocks) {
    if (block.kind === 'row') {
      loose.push(`<tr>${block.texts.map((t) => td(t.text.trim(), '')).join('')}</tr>`)
      continue
    }
    flush()
    parts.push(tableHtml(block.table))
  }
  flush()
  return (
    '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">' +
    `<head><meta charset="utf-8"></head><body>${parts.join('')}</body></html>`
  )
}

function tableHtml(table: Table): string {
  const cols = table.xs
    .slice(1)
    .map((x, i) => `<col width="${px(x - table.xs[i])}">`)
    .join('')
  const trs = table.ys.slice(1).map((y, r) => {
    const tds = table.cells
      .filter((cell) => cell.row === r)
      .sort((a, b) => a.col - b.col)
      .map((cell) => {
        const style = [
          cell.borders.top ? `border-top:${BORDER}` : '',
          cell.borders.right ? `border-right:${BORDER}` : '',
          cell.borders.bottom ? `border-bottom:${BORDER}` : '',
          cell.borders.left ? `border-left:${BORDER}` : '',
          `text-align:${cellAlign(cell)}`,
          'vertical-align:middle',
        ]
          .filter(Boolean)
          .join(';')
        const span = `${cell.colSpan > 1 ? ` colspan="${cell.colSpan}"` : ''}${cell.rowSpan > 1 ? ` rowspan="${cell.rowSpan}"` : ''}`
        return td(cellLines(cell).join('\n'), style, span)
      })
    return `<tr height="${px(y - table.ys[r])}">${tds.join('')}</tr>`
  })
  return `<table style="border-collapse:collapse">${cols}${trs.join('')}</table>`
}

function td(text: string, style: string, span = ''): string {
  const asText = text !== '' && !NUMBER.test(text) ? `mso-number-format:'\\@'` : ''
  const css = [style, asText].filter(Boolean).join(';')
  const body = text
    .split('\n')
    .map(escapeHtml)
    .join('<br style="mso-data-placement:same-cell">')
  return `<td${span}${css ? ` style="${css}"` : ''}>${body}</td>`
}

/**
 * The blocks as tab-separated text (what a spreadsheet takes when there is no HTML): a table's cells in their
 * columns, empty cells kept, a merged cell's text in its first cell, lines inside a cell quoted.
 */
export function toTsv(blocks: Block[]): string {
  const quote = (s: string) => (/[\t\n"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)
  const lines: string[] = []
  for (const block of blocks) {
    if (block.kind === 'row') {
      lines.push(block.texts.map((t) => t.text.trim()).join('\t'))
      continue
    }
    const { table } = block
    const grid = table.ys.slice(1).map(() => new Array<string>(table.xs.length - 1).fill(''))
    for (const cell of table.cells) grid[cell.row][cell.col] = quote(cellLines(cell).join('\n'))
    for (const row of grid) lines.push(row.join('\t').replace(/\t+$/, ''))
  }
  return lines.join('\n')
}
