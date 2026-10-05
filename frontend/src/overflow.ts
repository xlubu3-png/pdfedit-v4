import type { PageText, TextRun } from './types'

let context: OffscreenCanvasRenderingContext2D | null | undefined

function canvas(): OffscreenCanvasRenderingContext2D | null {
  if (context === undefined) {
    try {
      context = typeof OffscreenCanvas === 'undefined' ? null : new OffscreenCanvas(1, 1).getContext('2d')
    } catch {
      context = null
    }
  }
  return context
}

function measure(text: string, family: string, size: number, bold: boolean): number | null {
  const c = canvas()
  if (!c) return null
  c.font = `${bold ? 'bold ' : ''}${size}px '${family}'`
  return c.measureText(text).width
}

/**
 * How wide `text` comes out in a line's place, in points. The browser's font is only a stand-in for the
 * PDF's, so the result is the line's real width scaled by how much wider the new text is than the old
 * one when both are set in the same browser font.
 */
export function estimatedWidth(run: TextRun, text: string, family: string, size: number, bold: boolean): number {
  const before = measure(run.text, run.fontFamily, run.fontSize, run.bold)
  const after = measure(text, family, size, bold)
  if (before !== null && after !== null && before > 0) return (run.width * after) / before
  // No canvas to measure with: count characters.
  return run.width * (size / run.fontSize) * (text.length / Math.max(run.text.length, 1))
}

/** The x (points) the line must not run past: the next line on the same baseline, or the page's edge. */
export function rightLimit(run: TextRun, data: PageText): number {
  let limit = data.originX + data.pageWidth
  for (const other of data.runs) {
    if (other.index === run.index) continue
    const sameLine = Math.abs(other.y - run.y) < run.fontSize * 0.6
    if (sameLine && other.x > run.x && other.x - 1 < limit) limit = other.x - 1
  }
  return limit
}

/** Whether the edited text has grown enough to run into whatever stands to the right of the line. */
export function overflows(
  run: TextRun,
  data: PageText,
  text: string,
  family: string,
  size: number,
  bold: boolean,
  dx: number,
): boolean {
  if (text === run.text) return false
  const width = estimatedWidth(run, text, family, size, bold)
  if (width <= run.width + 1) return false // not longer than it was: whatever it touched before it touches now
  return run.x + dx + width > rightLimit(run, data) + 1
}
