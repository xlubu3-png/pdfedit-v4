import type { PageItem } from './types'

/**
 * Moving pages in the list: the pages in `moving` go together, in the order they have now. Every function gives
 * back the very same array when nothing would change, so that no empty step lands in the undo history.
 */

const sameOrder = (a: PageItem[], b: PageItem[]) => a.length === b.length && a.every((p, i) => p.key === b[i].key)

/** The list with the moving pages put together at `index` of the list without them (clamped to it). */
export function moveTo(pages: PageItem[], moving: ReadonlySet<string>, index: number): PageItem[] {
  const moved = pages.filter((p) => moving.has(p.key))
  if (moved.length === 0) return pages
  const rest = pages.filter((p) => !moving.has(p.key))
  const at = Math.max(0, Math.min(rest.length, Math.round(index)))
  const next = [...rest.slice(0, at), ...moved, ...rest.slice(at)]
  return sameOrder(next, pages) ? pages : next
}

/**
 * Dropping the moving pages at `slot` of the list as it is (before the page at that index; the length for the
 * end): where that is in the list without them.
 */
export function dropAt(pages: PageItem[], moving: ReadonlySet<string>, slot: number): PageItem[] {
  const before = pages.slice(0, Math.max(0, slot)).filter((p) => !moving.has(p.key)).length
  return moveTo(pages, moving, before)
}

/** One place earlier (-1) or later (+1), each moving page; pages already at that end stay. */
export function moveBy(pages: PageItem[], moving: ReadonlySet<string>, delta: -1 | 1): PageItem[] {
  const next = [...pages]
  const order = delta < 0 ? next.map((_, i) => i) : next.map((_, i) => next.length - 1 - i)
  for (const i of order) {
    const j = i + delta
    if (j < 0 || j >= next.length) continue
    if (moving.has(next[i].key) && !moving.has(next[j].key)) [next[i], next[j]] = [next[j], next[i]]
  }
  return sameOrder(next, pages) ? pages : next
}

/** Whether moving one place earlier (-1) or later (+1) would change anything. */
export function canMoveBy(pages: PageItem[], moving: ReadonlySet<string>, delta: -1 | 1): boolean {
  return moveBy(pages, moving, delta) !== pages
}

/** The moving pages put so that the first of them becomes page `number` (1-based) of the list. */
export function moveToNumber(pages: PageItem[], moving: ReadonlySet<string>, number: number): PageItem[] {
  return moveTo(pages, moving, number - 1)
}
