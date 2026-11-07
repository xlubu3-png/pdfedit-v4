import type { Table, TableCell } from './tableGrid'
import { rows } from './textSelect'
import type { PlacedText } from './textSelect'

/**
 * Tables drawn without lines: text set out in columns, as a price list, a parts list or a form's "name: value"
 * pairs are. They are found as kordoc and opendataloader find them, by how the text lines up: rows following one
 * another closely with several pieces each, and gaps down the page that no piece on those rows crosses - those
 * gaps are the column borders. Rows are parted halfway between lines. A table needs at least 2 columns, and 3
 * rows of which 2 have pieces in different columns; a line crossing a column gap (a heading, a sentence) ends it.
 */
export function borderlessTables(texts: PlacedText[]): Table[] {
  const pieces = texts.flatMap(splitAtGaps).filter((t) => t.text.trim() !== '' && (t.width ?? 0) > 0)
  if (pieces.length < 4) return []
  const lines = rows(pieces).map((row) => ({
    pieces: row,
    top: Math.min(...row.map((t) => t.top)),
    bottom: Math.max(...row.map((t) => t.top + t.height)),
    height: median(row.map((t) => t.letters ?? t.height)),
  }))
  const tables: Table[] = []
  let start = 0
  while (start < lines.length) {
    // The run of lines close below one another from here, as far as it still makes columns.
    let end = start + 1
    while (end < lines.length) {
      const gap = lines[end].top - lines[end - 1].bottom
      if (gap > ROW_GAP * Math.max(lines[end].height, lines[end - 1].height)) break
      end++
    }
    const found = tableIn(lines.slice(start, end))
    if (found) {
      tables.push(found.table)
      start += found.used
    } else {
      start++
    }
  }
  return tables
}

/** Lines further apart than this many line heights are not rows of one table. */
const ROW_GAP = 1.6
/** A gap between columns is at least this many letter heights wide (wider than the space between words). */
const COLUMN_GAP = 0.9
/** Inside one piece, a space at least this many letter heights wide parts two cells (a program padding columns). */
const WIDE_SPACE = 1.2

interface Line {
  pieces: PlacedText[]
  top: number
  bottom: number
  height: number
}

/** The table the lines start, and how many of them it takes; null when the first lines make no table. */
function tableIn(lines: Line[]): { table: Table; used: number } | null {
  let best: { table: Table; used: number } | null = null
  // The longest run from the first line that still has columns: each further line must not cross a column gap.
  for (let used = 3; used <= lines.length; used++) {
    const run = lines.slice(0, used)
    const gaps = columnGaps(run)
    if (gaps.length === 0) break
    const spread = run.filter((l) => columnsTaken(l, gaps) >= 2).length
    if (spread < 2) continue
    best = { table: build(run, gaps), used }
  }
  return best
}

/** The gaps (from, to) no piece of the lines lies across, between the leftmost and rightmost letters. */
function columnGaps(lines: Line[]): { from: number; to: number }[] {
  const all = lines.flatMap((l) => l.pieces)
  const height = median(lines.map((l) => l.height))
  const spans = all.map((t) => ({ from: t.left, to: t.left + (t.width ?? 0) })).sort((a, b) => a.from - b.from)
  const gaps: { from: number; to: number }[] = []
  let reach = spans[0].to
  for (const s of spans.slice(1)) {
    if (s.from - reach >= COLUMN_GAP * height) gaps.push({ from: reach, to: s.from })
    reach = Math.max(reach, s.to)
  }
  return gaps
}

/** How many columns the line has text in. */
function columnsTaken(line: Line, gaps: { from: number; to: number }[]): number {
  return new Set(line.pieces.map((t) => gaps.filter((g) => g.to <= t.left).length)).size
}

function build(lines: Line[], gaps: { from: number; to: number }[]): Table {
  const left = Math.min(...lines.flatMap((l) => l.pieces.map((t) => t.left)))
  const right = Math.max(...lines.flatMap((l) => l.pieces.map((t) => t.left + (t.width ?? 0))))
  const xs = [left, ...gaps.map((g) => (g.from + g.to) / 2), right]
  const ys = [
    lines[0].top,
    ...lines.slice(1).map((l, i) => (lines[i].bottom + l.top) / 2),
    lines[lines.length - 1].bottom,
  ]
  const none = { top: false, right: false, bottom: false, left: false }
  const cells: TableCell[] = []
  for (let r = 0; r < ys.length - 1; r++) {
    for (let c = 0; c < xs.length - 1; c++) {
      cells.push({
        row: r,
        col: c,
        rowSpan: 1,
        colSpan: 1,
        left: xs[c],
        top: ys[r],
        right: xs[c + 1],
        bottom: ys[r + 1],
        borders: none,
        texts: [],
      })
    }
  }
  return { xs, ys, cells, borderless: true }
}

/** A piece cut where it has a wide run of spaces inside it (columns a program padded with spaces). */
function splitAtGaps(text: PlacedText): PlacedText[] {
  const chars = text.chars
  if (!chars || chars.length !== text.text.length || !text.width) return [text]
  const end = text.left + text.width
  const wide = WIDE_SPACE * (text.letters ?? text.height)
  const out: PlacedText[] = []
  let from = 0
  let i = 0
  while (i < text.text.length) {
    if (text.text[i].trim() !== '') {
      i++
      continue
    }
    let j = i
    while (j < text.text.length && text.text[j].trim() === '') j++
    const gapEnd = j < chars.length ? chars[j] : end
    if (j < text.text.length && i > from && gapEnd - chars[i] >= wide) {
      out.push(piece(text, from, i, chars[i]))
      from = j
    }
    i = j
  }
  if (out.length === 0) return [text]
  out.push(piece(text, from, text.text.length, end))
  return out
}

function piece(text: PlacedText, from: number, to: number, right: number): PlacedText {
  const chars = text.chars!
  return {
    text: text.text.slice(from, to),
    left: chars[from],
    top: text.top,
    height: text.height,
    width: Math.max(0, right - chars[from]),
    chars: chars.slice(from, to),
    letters: text.letters,
  }
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted.length === 0 ? 0 : sorted[Math.floor(sorted.length / 2)]
}
