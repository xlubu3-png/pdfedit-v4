import { parseSession, serializeSession } from './session'
import type { PageItem } from './types'

const KEY = 'wintech-pdf-recent'

/** How many pieces of work put aside are remembered; the oldest gives way. */
export const MAX_RECENT = 10

/** A page list put aside to be taken up again: the server still holds its documents and their edits. */
export interface RecentWork {
  id: string
  /** What it is called in the list: its first file, and how many more. */
  title: string
  /** When it was put aside (milliseconds since 1970). */
  savedAt: number
  pages: PageItem[]
}

/** "견적서.pdf 외 2개": the first file's name and how many other files the pages come from. */
export function titleOf(pages: PageItem[]): string {
  const names = [...new Set(pages.map((p) => p.sourceFileName))]
  if (names.length === 0) return '빈 작업'
  return names.length === 1 ? names[0] : `${names[0]} 외 ${names.length - 1}개`
}

/** The list with this page list put aside at its top; work past {@link MAX_RECENT} is dropped (and given back). */
export function withWork(list: RecentWork[], pages: PageItem[], now: number): { list: RecentWork[]; dropped: RecentWork[] } {
  if (pages.length === 0) return { list, dropped: [] }
  const work: RecentWork = { id: `${now}-${pages[0].key}`, title: titleOf(pages), savedAt: now, pages }
  const all = [work, ...list]
  return { list: all.slice(0, MAX_RECENT), dropped: all.slice(MAX_RECENT) }
}

/**
 * The documents of `gone` that nothing else uses any more - not the other work put aside, not the open page
 * list: the ones to delete from the server with it.
 */
export function orphans(gone: RecentWork, kept: RecentWork[], open: PageItem[]): string[] {
  const used = new Set([...kept.flatMap((w) => w.pages), ...open].map((p) => p.documentId))
  return [...new Set(gone.pages.map((p) => p.documentId))].filter((id) => !used.has(id))
}

export function serializeRecent(list: RecentWork[]): string {
  return JSON.stringify(list.map((w) => ({ id: w.id, title: w.title, savedAt: w.savedAt, pages: serializeSession(w.pages) })))
}

/** The list from stored text; anything malformed is left out. */
export function parseRecent(text: string | null): RecentWork[] {
  if (!text) return []
  try {
    const parsed: unknown = JSON.parse(text)
    if (!Array.isArray(parsed)) return []
    const list: RecentWork[] = []
    for (const value of parsed) {
      if (typeof value !== 'object' || value === null) continue
      const v = value as Record<string, unknown>
      if (typeof v.id !== 'string' || typeof v.title !== 'string' || typeof v.savedAt !== 'number') continue
      const pages = parseSession(typeof v.pages === 'string' ? v.pages : null)
      if (pages.length > 0) list.push({ id: v.id, title: v.title, savedAt: v.savedAt, pages })
    }
    return list.slice(0, MAX_RECENT)
  } catch {
    return []
  }
}

export function loadRecent(): RecentWork[] {
  try {
    return parseRecent(localStorage.getItem(KEY))
  } catch {
    return []
  }
}

export function saveRecent(list: RecentWork[]): void {
  try {
    if (list.length === 0) localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, serializeRecent(list))
  } catch {
    // private mode or storage full: the app simply does not remember
  }
}

/** "오늘 14:05", "어제", "3일 전", or the date. */
export function whenText(savedAt: number, now: number): string {
  const day = (t: number) => {
    const d = new Date(t)
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  }
  const days = Math.round((day(now) - day(savedAt)) / 86_400_000)
  const d = new Date(savedAt)
  const two = (n: number) => String(n).padStart(2, '0')
  if (days <= 0) return `오늘 ${two(d.getHours())}:${two(d.getMinutes())}`
  if (days === 1) return '어제'
  if (days < 7) return `${days}일 전`
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`
}
