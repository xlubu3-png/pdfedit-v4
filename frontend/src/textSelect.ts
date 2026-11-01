import type { AddedText, OcrText, PageText, RunState } from './types'

/** A line of the page's text as the selection layer shows it, in pixels of the page on screen. */
export interface SelectableLine {
  key: string
  text: string
  left: number
  top: number
  width: number
  height: number
  /** Font size, pixels. */
  size: number
  /** The length the line has on the page, pixels: it is stretched to it. Absent: as the font sets it. */
  length?: number
  /** Letters one under the other, the line running down the page. */
  vertical?: boolean
  /** Degrees the line is turned, counter-clockwise as PDF counts, about its top-left corner. */
  turn?: number
}

/** A line this much taller than wide runs down the page (as the recognition tells it). */
const VERTICAL = 1.5
/** Letters take this share of their line's height. */
const LETTER_SHARE = 0.85
/** Distance between the baselines of a text box's lines, relative to its font size (as the server draws it). */
const LINE_SPACING = 1.2

/**
 * The page's text to select: its own lines (as edited and moved), what recognition read on a scan (less the lines
 * a box rewrites) and the text boxes put on it.
 *
 * @param scale pixels per point of the page on screen
 */
export function selectableLines(
  data: PageText,
  states: Map<number, RunState>,
  ocrLines: OcrText[],
  rewritten: Set<number>,
  added: AddedText[],
  scale: number,
): SelectableLine[] {
  const out: SelectableLine[] = []
  const left = (x: number) => (x - data.originX) * scale
  const top = (y: number) => (data.pageHeight - (y - data.originY)) * scale
  for (const run of data.runs) {
    const state = states.get(run.index)
    const text = state?.text ?? run.currentText
    if (!text.trim()) continue
    const height = (run.boxTop - run.boxBottom) * scale
    out.push({
      key: `run:${run.index}`,
      text,
      left: left(run.x + (state?.dx ?? 0)),
      top: top(run.boxTop + (state?.dy ?? 0)),
      width: run.width * scale,
      height,
      size: height * LETTER_SHARE,
      // Its letters as long as on the page; a line typed over has other letters and keeps its own length.
      length: text === run.text ? run.width * scale : undefined,
    })
  }
  ocrLines.forEach((line, i) => {
    if (rewritten.has(i) || !line.text.trim()) return
    const vertical = line.height >= VERTICAL * line.width
    const across = (vertical ? line.width : line.height) * scale
    out.push({
      key: `ocr:${i}`,
      text: vertical ? line.text.replace(/\s+/g, '') : line.text,
      left: left(line.x),
      top: top(line.y + line.height),
      width: line.width * scale,
      height: line.height * scale,
      size: across * LETTER_SHARE,
      length: (vertical ? line.height : line.width) * scale,
      vertical,
    })
  })
  for (const box of added) {
    box.text.split(/\r?\n/).forEach((text, i) => {
      if (!text.trim()) return
      const baseline = box.y - i * box.fontSize * LINE_SPACING
      out.push({
        key: `box:${box.id}:${i}`,
        text,
        left: left(box.x),
        top: top(baseline + box.fontSize),
        width: 0,
        height: box.fontSize * LINE_SPACING * scale,
        size: box.fontSize * scale,
        turn: box.rotation ?? 0,
      })
    })
  }
  return out
}

/**
 * The lines in the order they are read: top to bottom, and lines side by side (one row of a table) left to right.
 * Lines whose middles are within half a line of each other's height are side by side.
 */
export function readingOrder(lines: SelectableLine[]): SelectableLine[] {
  const middle = (l: SelectableLine) => l.top + l.height / 2
  const rows: SelectableLine[][] = []
  for (const line of [...lines].sort((a, b) => middle(a) - middle(b))) {
    const row = rows[rows.length - 1]
    const last = row?.[row.length - 1]
    if (last && Math.abs(middle(line) - middle(last)) <= Math.min(line.height, last.height) / 2) row.push(line)
    else rows.push([line])
  }
  return rows.flatMap((row) => row.sort((a, b) => a.left - b.left))
}
