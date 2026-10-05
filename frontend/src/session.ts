import type { PageItem } from './types'

const KEY = 'wintech-pdf-session'

/** Pages as they are stored; the thumbnail revision is not (it only forces a re-fetch). */
type StoredPage = Pick<PageItem, 'key' | 'documentId' | 'sourceFileName' | 'pageIndex' | 'rotation'>

const ROTATIONS = [0, 90, 180, 270]

function isStoredPage(value: unknown): value is StoredPage {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    typeof v.key === 'string' &&
    typeof v.documentId === 'string' &&
    typeof v.sourceFileName === 'string' &&
    typeof v.pageIndex === 'number' &&
    Number.isInteger(v.pageIndex) &&
    v.pageIndex >= 0 &&
    typeof v.rotation === 'number' &&
    ROTATIONS.includes(v.rotation)
  )
}

export function serializeSession(pages: PageItem[]): string {
  const stored: StoredPage[] = pages.map((p) => ({
    key: p.key,
    documentId: p.documentId,
    sourceFileName: p.sourceFileName,
    pageIndex: p.pageIndex,
    rotation: p.rotation,
  }))
  return JSON.stringify(stored)
}

/** Pages from stored text; anything that is not a list of well-formed pages gives an empty list. */
export function parseSession(text: string | null): PageItem[] {
  if (!text) return []
  try {
    const parsed: unknown = JSON.parse(text)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(isStoredPage).map((p) => ({ ...p, textRevision: 0 }))
  } catch {
    return []
  }
}

/** Remembers the page list in this browser, so a reload picks the work up again. */
export function saveSession(pages: PageItem[]): void {
  try {
    if (pages.length === 0) localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, serializeSession(pages))
  } catch {
    // private mode or storage full: the app simply does not remember
  }
}

export function loadSession(): PageItem[] {
  try {
    return parseSession(localStorage.getItem(KEY))
  } catch {
    return []
  }
}

export function clearSession(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // nothing to clear
  }
}
