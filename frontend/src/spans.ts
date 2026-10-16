import type { Span } from './types'

/**
 * Partial formatting: parts of a line that are bold or in another colour than the rest. A span covers the
 * characters `start` (included) to `end` (excluded); a null `bold` or `color` leaves that to the line.
 * The server keeps the same rules (RunEdit.java), so what is drawn here is what gets saved.
 */

/** What one character looks like once the spans are laid over the line's own look. */
interface CharLook {
  bold: boolean | null
  color: string | null
}

/** The line's own look, to tell which spans change nothing. */
export interface LineLook {
  bold: boolean
  color: string
}

const sameLook = (a: CharLook, b: CharLook) => a.bold === b.bold && a.color === b.color

function looksOf(spans: Span[], length: number): CharLook[] {
  const looks: CharLook[] = Array.from({ length }, () => ({ bold: null, color: null }))
  for (const span of spans) {
    for (let i = Math.max(0, span.start); i < Math.min(length, span.end); i++) {
      looks[i] = { bold: span.bold, color: span.color }
    }
  }
  return looks
}

function spansOf(looks: CharLook[]): Span[] {
  const out: Span[] = []
  let start = 0
  for (let i = 1; i <= looks.length; i++) {
    if (i < looks.length && sameLook(looks[i], looks[start])) continue
    const look = looks[start]
    if (look && (look.bold !== null || look.color !== null)) out.push({ start, end: i, bold: look.bold, color: look.color })
    start = i
  }
  return out
}

/** Spans fit for a text of `length` characters: clipped, in order, neighbours with the same look joined. */
export function cleanSpans(spans: Span[], length: number): Span[] {
  return spansOf(looksOf(spans, length))
}

/**
 * Where the spans of `oldText` lie once it has become `newText`. What is typed inside a span joins it, what is
 * typed beside it does not, and a span whose letters were all replaced covers the replacement.
 */
export function remapSpans(spans: Span[], oldText: string, newText: string): Span[] {
  if (spans.length === 0 || oldText === newText) return spans
  const limit = Math.min(oldText.length, newText.length)
  let prefix = 0
  while (prefix < limit && oldText[prefix] === newText[prefix]) prefix++
  let suffix = 0
  while (suffix < limit - prefix && oldText[oldText.length - 1 - suffix] === newText[newText.length - 1 - suffix]) suffix++
  const oldEnd = oldText.length - suffix
  const newEnd = newText.length - suffix
  const delta = newEnd - oldEnd
  const out: Span[] = []
  for (const span of spans) {
    let start: number
    let end: number
    if (span.end <= prefix) {
      start = span.start
      end = span.end
    } else if (span.start >= oldEnd) {
      start = span.start + delta
      end = span.end + delta
    } else {
      start = Math.min(span.start, prefix)
      end = span.end > oldEnd ? span.end + delta : newEnd
    }
    if (end > start) out.push({ ...span, start, end })
  }
  return cleanSpans(out, newText.length)
}

/** Bold on or off for `[start, end)`: off only when every one of those characters is bold already. */
export function boldToggleFor(spans: Span[], start: number, end: number, line: LineLook): boolean {
  const looks = looksOf(spans, end)
  for (let i = start; i < end; i++) {
    if (!(looks[i]?.bold ?? line.bold)) return true
  }
  return false
}

/** Lays a weight or colour over `[start, end)` of the text; a value equal to the line's own is no span at all. */
export function formatRange(
  spans: Span[],
  start: number,
  end: number,
  length: number,
  patch: { bold?: boolean; color?: string },
  line: LineLook,
): Span[] {
  const looks = looksOf(spans, length)
  for (let i = Math.max(0, start); i < Math.min(length, end); i++) {
    const look = { ...looks[i] }
    if (patch.bold !== undefined) look.bold = patch.bold === line.bold ? null : patch.bold
    if (patch.color !== undefined) look.color = patch.color.toLowerCase() === line.color.toLowerCase() ? null : patch.color
    looks[i] = look
  }
  return spansOf(looks)
}

/** A change to the whole line takes over what the spans said about that one thing. */
export function clearField(spans: Span[], field: 'bold' | 'color'): Span[] {
  const length = spans.reduce((most, span) => Math.max(most, span.end), 0)
  return spansOf(looksOf(spans, length).map((look) => ({ ...look, [field]: null })))
}
