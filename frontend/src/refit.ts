import type { AddedText, Cover, OcrText } from './types'

/**
 * The recognized line a rewritten box stands for: of the lines whose middle its cover holds, the one it overlaps
 * most; null when the cover holds none (the page was recognized again since, or not at all).
 */
export function lineOfCover(cover: Cover, lines: OcrText[]): number | null {
  let best: number | null = null
  let bestOverlap = 0
  lines.forEach((line, i) => {
    const mx = line.x + line.width / 2
    const my = line.y + line.height / 2
    if (mx < cover.x || mx > cover.x + cover.width || my < cover.y || my > cover.y + cover.height) return
    const w = Math.min(line.x + line.width, cover.x + cover.width) - Math.max(line.x, cover.x)
    const h = Math.min(line.y + line.height, cover.y + cover.height) - Math.max(line.y, cover.y)
    const overlap = Math.max(0, w) * Math.max(0, h)
    if (best === null || overlap > bestOverlap) {
      best = i
      bestOverlap = overlap
    }
  })
  return best
}

/**
 * The box rewritten again in `look` (the line told afresh): its font, size, colour, place and cover are the new
 * look's; its id stays, and so does its text when the user changed it (with its formatting) - left as it was read,
 * it takes the new reading, spaced as the scan is.
 */
export function refitted(box: AddedText, look: Omit<AddedText, 'id'>): AddedText {
  const changed = box.cover?.read == null || box.text !== box.cover.read
  return {
    ...look,
    id: box.id,
    text: changed ? box.text : look.text,
    spans: changed ? box.spans : (look.spans ?? []),
  }
}
