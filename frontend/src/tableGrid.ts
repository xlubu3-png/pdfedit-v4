import { borderlessTables } from './borderless'
import { rows } from './textSelect'
import type { PlacedText } from './textSelect'

/** The straight lines drawn on a page (a table's borders), in points from its top-left corner. */
export interface Rulings {
  horizontal: { y: number; x1: number; x2: number }[]
  vertical: { x: number; y1: number; y2: number }[]
  /**
   * Degrees a scan was turned (clockwise, about pivotX, pivotY) to be set straight before its lines were found:
   * the lines are in the straightened page, and the text is turned the same way to be laid against them.
   */
  turn?: number
  pivotX?: number
  pivotY?: number
}

/** How a page was turned straight: degrees clockwise about a point (points from the top-left). */
export interface Turn {
  degrees: number
  x: number
  y: number
}

export function turnOf(rulings: Rulings): Turn | null {
  return rulings.turn ? { degrees: rulings.turn, x: rulings.pivotX ?? 0, y: rulings.pivotY ?? 0 } : null
}

/** The text moved as the page was turned straight, so it lies in the cells the straightened lines make. */
export function straighten(texts: PlacedText[], turn: Turn | null): PlacedText[] {
  if (!turn || turn.degrees === 0) return texts
  const r = (turn.degrees * Math.PI) / 180
  const cos = Math.cos(r)
  const sin = Math.sin(r)
  return texts.map((t) => {
    // Where its middle goes; the text keeps its shape (a few degrees), only its place moves.
    const mx = t.left + (t.width ?? 0) / 2 - turn.x
    const my = t.top + t.height / 2 - turn.y
    const dx = turn.x + mx * cos - my * sin - (mx + turn.x)
    const dy = turn.y + mx * sin + my * cos - (my + turn.y)
    return { ...t, left: t.left + dx, top: t.top + dy, chars: t.chars?.map((c) => c + dx) }
  })
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
  /** Found from how its text lines up in columns, not from lines drawn (see borderless.ts). */
  borderless?: boolean
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

/** A table set out by hand (표 칸 바로잡기): its column and row borders, points, edges included. */
export interface Grid {
  xs: number[]
  ys: number[]
}

/** The table a grid sets out: every cell on its own, every border drawn. */
export function gridTable(grid: Grid): Table {
  const all = { top: true, right: true, bottom: true, left: true }
  const cells: TableCell[] = []
  for (let r = 0; r < grid.ys.length - 1; r++) {
    for (let c = 0; c < grid.xs.length - 1; c++) {
      cells.push({
        row: r,
        col: c,
        rowSpan: 1,
        colSpan: 1,
        left: grid.xs[c],
        top: grid.ys[r],
        right: grid.xs[c + 1],
        bottom: grid.ys[r + 1],
        borders: all,
        texts: [],
      })
    }
  }
  return { xs: [...grid.xs], ys: [...grid.ys], cells }
}

/** A table's grid (its borders), to be set out by hand from it. */
export function gridOf(table: Table): Grid {
  return { xs: [...table.xs], ys: [...table.ys] }
}

/**
 * The tables to use: those set out by hand, and of the ones found on the page those not lying where one set out by
 * hand does (more than half of either over the other) - the hand-made one is what the user wants there.
 */
export function withGrids(found: Table[], grids: Grid[]): Table[] {
  if (grids.length === 0) return found
  return [...notCovered(found, grids), ...grids.map(gridTable)].sort((a, b) => a.ys[0] - b.ys[0])
}

/** The tables found on the page that no table set out by hand lies over (more than half of either). */
export function notCovered(found: Table[], grids: Grid[]): Table[] {
  const area = (l: number, t: number, r: number, b: number) => Math.max(0, r - l) * Math.max(0, b - t)
  const box = (t: { xs: number[]; ys: number[] }) => [t.xs[0], t.ys[0], t.xs[t.xs.length - 1], t.ys[t.ys.length - 1]]
  return found.filter(
    (table) =>
      !grids.some((g) => {
        const [al, at, ar, ab] = box(table)
        const [bl, bt, br, bb] = box(g)
        const both = area(Math.max(al, bl), Math.max(at, bt), Math.min(ar, br), Math.min(ab, bb))
        return both > 0.5 * Math.min(area(al, at, ar, ab), area(bl, bt, br, bb))
      }),
  )
}

/**
 * The text laid out as it is on the page: the tables with each piece of text in the cell its middle is in, and the
 * rest as rows. With `onlyPicked`, a table keeps just the rows and columns the text is in (a part dragged over);
 * otherwise it is whole, empty cells and all. A table no text falls in is left out.
 */
export function layout(texts: PlacedText[], tables: Table[], onlyPicked: boolean): Block[] {
  const drawn = fill(texts, tables)
  // The text outside the drawn tables may be set out in columns with no lines: tables of their own.
  const found = fill(drawn.loose, borderlessTables(drawn.loose))
  const filled = [...drawn.filled, ...found.filled]
  const loose = found.loose
  const blocks: Block[] = []
  for (const whole of filled) {
    if (!whole.cells.some((c) => c.texts.length > 0)) continue
    const table = splitStackedRows(splitAreas(whole))
    const used = table.cells.filter((c) => c.texts.length > 0)
    const kept = onlyPicked ? crop(table, used) : table
    blocks.push({ kind: 'table', table: kept, top: kept.ys[0] })
  }
  for (const row of rows(loose)) blocks.push({ kind: 'row', texts: row, top: row[0].top })
  return blocks.sort((a, b) => a.top - b.top)
}

/** The texts put in the tables' cells (each in the cell its middle is in, cut at borders), and the rest. */
function fill(texts: PlacedText[], tables: Table[]): { filled: Table[]; loose: PlacedText[] } {
  const filled = tables.map((t) => ({ ...t, cells: t.cells.map((c) => ({ ...c, texts: [] as PlacedText[] })) }))
  const loose: PlacedText[] = []
  const cellAt = (x: number, y: number) =>
    filled.flatMap((t) => t.cells).find((c) => !c.container && x >= c.left && x <= c.right && y >= c.top && y <= c.bottom)
  for (const text of texts.flatMap((t) => (filled.length > 0 ? splitAtBorders(t, cellAt) : [t]))) {
    if (text.text.trim() === '') continue
    const x = text.left + (text.width ?? 0) / 2
    const y = text.top + text.height / 2
    const cell = cellAt(x, y)
    if (cell) cell.texts.push(text)
    else loose.push(text)
  }
  return { filled, loose }
}

/** A line that is an amount: "1,250", "-3.5", "220,300원", "△19,454원" (kordoc's number cell). */
const AMOUNT = /^[-+−△▲]?\s*[\d,]+(\.\d+)?\s*(원|-원)?$/

/**
 * Rows set one under the other inside one row of a table (as some programs lay out a quote: the items stacked in
 * one cell, their amounts stacked in the next, with no border between them) are split into their own rows, so each
 * item and its amount get a row of the sheet. A row is split when at least two of its cells have two or more lines,
 * one of them a column of amounts (80 % of its lines amounts) with the most lines, and every other such cell's
 * lines are short (labels, not sentences) and no more. Cells of the row with one line, and those reaching over it,
 * stretch over the new rows. After kordoc's ocr-stacked-rows (MIT, Copyright (c) 2026 chrisryugj).
 */
function splitStackedRows(table: Table): Table {
  let current = table
  for (let r = current.ys.length - 2; r >= 0; r--) current = splitRow(current, r)
  return current
}

function splitRow(table: Table, r: number): Table {
  const inRow = table.cells.filter((c) => c.row === r && c.rowSpan === 1 && !c.container)
  const lined = inRow.map((cell) => ({ cell, lines: rows(cell.texts) })).filter((x) => x.lines.length >= 2)
  if (lined.length < 2) return table
  const text = (line: PlacedText[]) => line.map((t) => t.text.trim()).join(' ')
  const amounts = lined.filter((x) => x.lines.filter((l) => AMOUNT.test(text(l))).length >= x.lines.length * 0.8)
  if (amounts.length === 0) return table
  const k = Math.max(...amounts.map((x) => x.lines.length))
  if (lined.some((x) => x.lines.length > k || (!amounts.includes(x) && x.lines.some((l) => text(l).length > 20)))) {
    return table
  }
  // Where the new rows part: halfway between the lines of the column of amounts with the most of them.
  const guide = amounts.find((x) => x.lines.length === k)!.lines
  const bottom = (l: PlacedText[]) => Math.max(...l.map((t) => t.top + t.height))
  const top = (l: PlacedText[]) => Math.min(...l.map((t) => t.top))
  const cuts: number[] = []
  for (let i = 0; i + 1 < guide.length; i++) {
    const cut = (bottom(guide[i]) + top(guide[i + 1])) / 2
    if (cut <= table.ys[r] || cut >= table.ys[r + 1] || (cuts.length > 0 && cut <= cuts[cuts.length - 1])) return table
    cuts.push(cut)
  }
  const ys = [...table.ys.slice(0, r + 1), ...cuts, ...table.ys.slice(r + 1)]
  const more = cuts.length
  const split = new Set(lined.map((x) => x.cell))
  const cells: TableCell[] = []
  for (const cell of table.cells) {
    if (split.has(cell)) {
      const edges = [table.ys[r], ...cuts, table.ys[r + 1]]
      for (let j = 0; j <= more; j++) {
        cells.push({
          ...cell,
          row: r + j,
          top: edges[j],
          bottom: edges[j + 1],
          borders: { ...cell.borders, top: j === 0 && cell.borders.top, bottom: j === more && cell.borders.bottom },
          texts: cell.texts.filter((t) => {
            const middle = t.top + t.height / 2
            return middle >= edges[j] && (middle < edges[j + 1] || j === more)
          }),
        })
      }
    } else if (cell.row > r) {
      cells.push({ ...cell, row: cell.row + more })
    } else if (cell.row + cell.rowSpan > r) {
      cells.push({ ...cell, rowSpan: cell.rowSpan + more })
    } else {
      cells.push(cell)
    }
  }
  return { ...table, ys, cells }
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
      letters: text.letters,
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
export const NUMBER = /^[-+]?(\d{1,3}(,\d{3})+|0|[1-9]\d*)(\.\d+)?$/

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
/** Points to the pixels Excel counts widths and heights in. */
const px = (pt: number) => Math.max(1, Math.round((pt * 96) / 72))
const BORDER = '.5pt solid #000'
/** A line of text's box is this much taller than its font size. */
const BOX_PER_FONT = 1.3

/** A cell of the sheet the copied part becomes: where it is, what it says and how it looks. */
export interface SheetCell {
  row: number
  col: number
  rowSpan: number
  colSpan: number
  lines: string[]
  borders: TableCell['borders'] | null
  align: 'left' | 'center' | 'right'
  /** Font size in points, from the text's height on the page; null for an empty cell. */
  size: number | null
}

/** The copied part as one sheet: column widths and row heights (points), and its cells. */
export interface Sheet {
  widths: number[]
  heights: number[]
  cells: SheetCell[]
}

/**
 * The font size (points) for text this tall on the page. Heights near the page's usual one are taken as it: a PDF
 * gives lines of the same size slightly different heights, and the sheet would show rows in different sizes.
 */
const fontSize = (texts: PlacedText[], usual: number): number | null => {
  if (texts.length === 0) return null
  const tallest = Math.max(...texts.map((t) => t.letters ?? t.height))
  const height = tallest >= usual * 0.8 && tallest <= usual * 1.25 ? usual : tallest
  return Math.min(24, Math.max(6, Math.round((height / BOX_PER_FONT) * 2) / 2))
}

/**
 * The blocks laid out on one grid, as the page has them: the columns are the tables' column borders (tables that
 * share a border share the sheet column) and the edges of the copied part, so each table sits where it is on the
 * page and a line outside the tables starts in the column it starts in on the page. One grid instead of a table
 * per block: Excel puts every table in the same columns, and their widths would fight.
 */
export function toSheet(blocks: Block[]): Sheet {
  const tables = blocks.flatMap((b) => (b.kind === 'table' ? [b.table] : []))
  const loose = blocks.flatMap((b) => (b.kind === 'row' ? b.texts : []))
  const lefts = [...tables.map((t) => t.xs[0]), ...loose.map((t) => t.left)]
  const rights = [...tables.map((t) => t.xs[t.xs.length - 1]), ...loose.map((t) => t.left + (t.width ?? 0))]
  const xs = clusters([Math.min(...lefts), ...tables.flatMap((t) => t.xs), Math.max(...rights)])
  /** The sheet column border nearest to a table's border. */
  const border = (x: number) => {
    let best = 0
    xs.forEach((b, i) => {
      if (Math.abs(b - x) < Math.abs(xs[best] - x)) best = i
    })
    return best
  }
  /** The sheet column a line of text starting at x starts in. */
  const columnAt = (x: number) => {
    let k = 0
    while (k + 1 < xs.length - 1 && xs[k + 1] <= x + 1) k++
    return k
  }
  const all = [...tables.flatMap((t) => t.cells.flatMap((c) => c.texts)), ...loose]
    .map((t) => t.letters ?? t.height)
    .sort((a, b) => a - b)
  const usual = all.length > 0 ? all[Math.floor(all.length / 2)] : 0
  // Rows: the tables' row borders (tables side by side share them), and the lines of text outside every table.
  // A line beside a table takes the table's row it is in, as in Excel.
  const rowsOf = blocks.flatMap((b) => (b.kind === 'row' ? [b.texts] : []))
  const extent = (texts: PlacedText[]) => ({
    top: Math.min(...texts.map((t) => t.top)),
    bottom: Math.max(...texts.map((t) => t.top + t.height)),
  })
  const besideTable = (middle: number) => tables.some((t) => middle >= t.ys[0] && middle <= t.ys[t.ys.length - 1])
  const outside = rowsOf.filter((texts) => {
    const e = extent(texts)
    return !besideTable((e.top + e.bottom) / 2)
  })
  const ys = clusters([
    ...tables.flatMap((t) => t.ys),
    ...outside.flatMap((texts) => {
      const e = extent(texts)
      return [e.top, e.bottom]
    }),
  ])
  const nearestRow = (y: number) => {
    let best = 0
    ys.forEach((b, i) => {
      if (Math.abs(b - y) < Math.abs(ys[best] - y)) best = i
    })
    return best
  }
  const rowAt = (y: number) => {
    let k = 0
    while (k + 1 < ys.length - 1 && ys[k + 1] <= y) k++
    return k
  }
  const cells: SheetCell[] = []
  for (const table of tables) {
    for (const cell of table.cells) {
      // A cell another table sits in gives way to it.
      if (cell.container) continue
      const col = border(table.xs[cell.col])
      const end = Math.max(col + 1, border(table.xs[cell.col + cell.colSpan]))
      const row = nearestRow(table.ys[cell.row])
      const rowEnd = Math.max(row + 1, nearestRow(table.ys[cell.row + cell.rowSpan]))
      cells.push({
        row,
        col,
        rowSpan: rowEnd - row,
        colSpan: end - col,
        lines: cellLines(cell),
        borders: cell.borders,
        align: cellAlign(cell),
        size: fontSize(cell.texts, usual),
      })
    }
  }
  // Pieces of a line starting in one column go together; a long one runs on over the empty columns after it.
  const looseCells = new Map<string, { row: number; col: number; texts: PlacedText[] }>()
  for (const texts of rowsOf) {
    const e = extent(texts)
    const middle = (e.top + e.bottom) / 2
    const row = besideTable(middle) ? rowAt(middle) : nearestRow(e.top)
    for (const t of [...texts].sort((a, b) => a.left - b.left)) {
      const visible = t.left + ((t.text.length - t.text.trimStart().length) / Math.max(1, t.text.length)) * (t.width ?? 0)
      const col = columnAt(visible)
      const key = `${row}:${col}`
      if (!looseCells.has(key)) looseCells.set(key, { row, col, texts: [] })
      looseCells.get(key)!.texts.push(t)
    }
  }
  for (const { row, col, texts } of looseCells.values()) {
    cells.push({
      row,
      col,
      rowSpan: 1,
      colSpan: 1,
      lines: [texts.map((t) => t.text.trim()).join(' ')],
      borders: null,
      align: 'left',
      size: fontSize(texts, usual),
    })
  }
  const heights = ys.slice(1).map((y, i) => Math.max(1, y - ys[i]))
  // Readable letters and rows tall enough for them, as Excel makes rows when something is pasted: a cell keeps the
  // page's letter size (at least MIN_READABLE), and a row grows to the lines its cells need at their column width
  // (the letters of the sheet's font take more room than the page's). Rows are never shorter than on the page.
  const widths = xs.slice(1).map((x, i) => x - xs[i])
  const usualSize = Math.max(MIN_READABLE, Math.round((usual / BOX_PER_FONT) * 2) / 2)
  for (const cell of cells) {
    if (cell.size === null) continue
    // Letters bigger than the page's usual ones (a heading, or a misread recognized line) shrink toward the usual
    // size when the cell is too narrow for them, before the text wraps: a title fitting its wide cell stays big.
    if (cell.borders && cell.size > usualSize) {
      const room = widths.slice(cell.col, cell.col + cell.colSpan).reduce((a, w) => a + w, 0) - CELL_PADDING
      const widest = Math.max(...cell.lines.map((line) => textWidth(line, 1)))
      if (widest * cell.size > room) {
        cell.size = Math.max(usualSize, Math.floor((room / Math.max(widest, 0.01)) * 2) / 2)
      }
    }
    cell.size = Math.max(MIN_READABLE, cell.size)
  }
  const need = (cell: SheetCell) => {
    if (cell.size === null) return 0
    const size = cell.size
    const room = Math.max(size, widths.slice(cell.col, cell.col + cell.colSpan).reduce((a, w) => a + w, 0) - CELL_PADDING)
    // A line outside the tables runs on over the empty cells beside it; a table's cell wraps inside its width.
    const lines = cell.borders
      ? cell.lines.reduce((n, line) => n + Math.max(1, Math.ceil(textWidth(line, size) / room)), 0)
      : cell.lines.length
    return lines * size * LINE_HEIGHT + ROW_PADDING
  }
  // Single-row cells first, then cells over several rows add what they still lack to their last row.
  for (const cell of cells) {
    if (cell.rowSpan === 1) heights[cell.row] = Math.max(heights[cell.row], need(cell))
  }
  for (const cell of cells) {
    if (cell.rowSpan === 1) continue
    const last = cell.row + cell.rowSpan - 1
    const tall = heights.slice(cell.row, last + 1).reduce((a, h) => a + h, 0)
    const lack = need(cell) - tall
    if (lack > 0 && last < heights.length) heights[last] += lack
  }
  for (let i = 0; i < heights.length; i++) heights[i] = Math.round(heights[i] * 4) / 4
  return { widths, heights, cells }
}

/** Letters smaller than this (points) are hard to read in a sheet; a page's small print is made this big. */
const MIN_READABLE = 8
/** A line of the sheet's font is this many times its size tall (맑은 고딕 with Excel's line spacing). */
const LINE_HEIGHT = 1.35
/** Room a cell keeps above and below its text, and beside it (points). */
const ROW_PADDING = 3
const CELL_PADDING = 4

/** About how wide (points) the text is in the sheet's font: Hangul and other wide letters a full size, the rest about half. */
function textWidth(text: string, size: number): number {
  let em = 0
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0
    em += code >= 0x1100 && code <= 0xffdc && !(code >= 0xff61 && code <= 0xffdc) ? 1 : ch === ' ' ? 0.3 : 0.58
  }
  return em * size
}

/**
 * The blocks as HTML a spreadsheet pastes as cells (Excel, Google Sheets, Hangul), laid out as on the page: one
 * grid with the page's column widths and row heights, the tables' merged cells and borders, the text's size and
 * alignment, text kept as text (no dates made of "1-A"), lines inside a cell kept in the cell, and no wrapping of
 * a one-line cell.
 */
export function toHtml(blocks: Block[]): string {
  const head =
    '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">' +
    '<head><meta charset="utf-8"></head><body>'
  if (blocks.length === 0) return `${head}</body></html>`
  const sheet = toSheet(blocks)
  const columns = sheet.widths.length
  const taken = sheet.heights.map(() => new Array<boolean>(columns).fill(false))
  const at = new Map(sheet.cells.map((c) => [`${c.row}:${c.col}`, c] as const))
  const cols = sheet.widths.map((w) => `<col width="${px(w)}" style="width:${w.toFixed(1)}pt">`).join('')
  const trs = sheet.heights.map((h, r) => {
    const tds: string[] = []
    for (let c = 0; c < columns; c++) {
      if (taken[r][c]) continue
      const cell = at.get(`${r}:${c}`)
      if (!cell) {
        tds.push('<td></td>')
        continue
      }
      for (let i = r; i < Math.min(r + cell.rowSpan, taken.length); i++) {
        for (let k = c; k < Math.min(c + cell.colSpan, columns); k++) taken[i][k] = true
      }
      const b = cell.borders
      const style = [
        b?.top ? `border-top:${BORDER}` : '',
        b?.right ? `border-right:${BORDER}` : '',
        b?.bottom ? `border-bottom:${BORDER}` : '',
        b?.left ? `border-left:${BORDER}` : '',
        `text-align:${cell.align}`,
        'vertical-align:middle',
        cell.size ? `font-size:${cell.size}pt` : '',
        // Text in a table wraps inside its cell when the sheet's column is narrower than the page's (Excel keeps
        // its own column widths on a paste); text outside the tables runs on over the empty cells beside it.
        cell.borders || cell.lines.length > 1 ? 'white-space:normal' : 'white-space:nowrap',
      ]
        .filter(Boolean)
        .join(';')
      const span = `${cell.colSpan > 1 ? ` colspan="${cell.colSpan}"` : ''}${cell.rowSpan > 1 ? ` rowspan="${cell.rowSpan}"` : ''}`
      tds.push(td(cell.lines.join('\n'), style, span))
    }
    return `<tr height="${px(h)}" style="height:${h.toFixed(1)}pt">${tds.join('')}</tr>`
  })
  // The page's widths, not the browser's: a program that lays tables out itself (Hangul, Word) keeps the columns.
  const width = sheet.widths.reduce((a, w) => a + w, 0)
  const table = `<table style="border-collapse:collapse;table-layout:fixed;width:${width.toFixed(1)}pt" width="${px(width)}">`
  return `${head}${table}${cols}${trs.join('')}</table></body></html>`
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
 * The blocks as tab-separated text (what a spreadsheet takes when there is no HTML), on the same grid as the HTML:
 * each text in its column, empty cells kept, a merged cell's text in its first cell, lines inside a cell quoted.
 */
export function toTsv(blocks: Block[]): string {
  if (blocks.length === 0) return ''
  const sheet = toSheet(blocks)
  const quote = (s: string) => (/[\t\n"]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)
  const grid = sheet.heights.map(() => new Array<string>(sheet.widths.length).fill(''))
  for (const cell of sheet.cells) grid[cell.row][cell.col] = quote(cell.lines.join('\n'))
  return grid.map((row) => row.join('\t').replace(/\t+$/, '')).join('\n')
}
