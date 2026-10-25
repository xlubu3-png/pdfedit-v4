import type { CompareResult, PageItem, PageSize, PdfArea, TextChange } from './types'

/** A document in the page list, once: what the comparison offers to pick from. */
export interface ListedDocument {
  documentId: string
  fileName: string
  /** Its pages in the list (some may have been deleted from it). */
  pages: number
}

export function documentsIn(pages: PageItem[]): ListedDocument[] {
  const byId = new Map<string, ListedDocument>()
  for (const p of pages) {
    const known = byId.get(p.documentId)
    if (known) known.pages += 1
    else byId.set(p.documentId, { documentId: p.documentId, fileName: p.sourceFileName, pages: 1 })
  }
  return [...byId.values()]
}

/** The changes of one pair of pages: page i of the first document next to page i of the second. */
export interface PageChanges {
  index: number
  removed: TextChange[]
  added: TextChange[]
}

/** How many page pairs there are: the longer document's page count. */
export function pairCount(result: CompareResult): number {
  return Math.max(result.pagesA.length, result.pagesB.length)
}

/** The page pairs that have a text change, in page order. */
export function changedPages(result: CompareResult): PageChanges[] {
  const pages = new Map<number, PageChanges>()
  const at = (index: number) => {
    let entry = pages.get(index)
    if (!entry) {
      entry = { index, removed: [], added: [] }
      pages.set(index, entry)
    }
    return entry
  }
  for (const change of result.removed) at(change.page).removed.push(change)
  for (const change of result.added) at(change.page).added.push(change)
  return [...pages.values()].sort((x, y) => x.index - y.index)
}

/** Where an area of a page is drawn over a picture of that page shown `scale` pixels per point. */
export function areaStyle(area: PdfArea, page: PageSize, scale: number) {
  return {
    left: (area.x - page.originX) * scale,
    top: (page.height - (area.y + area.height - page.originY)) * scale,
    width: area.width * scale,
    height: area.height * scale,
  }
}
