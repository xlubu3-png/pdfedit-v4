import { fetchPageOcr, fetchPageText, fetchRulings } from './api'
import { findTables, layout, toSheet } from './tableGrid'
import type { PlacedText } from './textSelect'
import type { Sheet, Table } from './tableGrid'
import { placed, rewrittenLines, selectableLines } from './textSelect'
import type { PageItem } from './types'
import { sheetsToXlsx } from './xlsx'

const EMPTY: Sheet = { widths: [], heights: [], cells: [] }

/** The page's text as one sheet laid out as the page is: its tables in their cells, the rest where it stands. */
export function sheetOf(texts: PlacedText[], rulings: Parameters<typeof findTables>[0]): Sheet {
  return sheetWith(texts, findTables(rulings))
}

/** {@link sheetOf} with the page's tables already found. */
export function sheetWith(texts: PlacedText[], tables: Table[]): Sheet {
  const blocks = layout(texts, tables, false)
  return blocks.length > 0 ? toSheet(blocks) : EMPTY
}

/** The name of a workbook made from a PDF: "계약서.pdf" → "계약서.xlsx", with "_3쪽" for one page of several. */
export function workbookName(sourceFileName: string, page?: number): string {
  const stem = sourceFileName.replace(/\.pdf$/i, '').replace(/[\\/:*?"<>|]/g, '_').trim() || '표'
  return `${stem}${page === undefined ? '' : `_${page}쪽`}.xlsx`
}

/**
 * A page's sheet from what the server holds: its text as edited, the text recognized on a scan and the boxes put
 * on it, and the lines of its tables.
 */
async function pageSheet(page: PageItem): Promise<Sheet> {
  const [data, rulings, ocr] = await Promise.all([
    fetchPageText(page.documentId, page.pageIndex),
    fetchRulings(page.documentId, page.pageIndex).catch(() => ({ horizontal: [], vertical: [] })),
    fetchPageOcr(page.documentId, page.pageIndex).catch(() => ({ lines: [] })),
  ])
  const lines = selectableLines(data, new Map(), ocr.lines, rewrittenLines(ocr.lines, data.added ?? []), data.added ?? [], 1)
  return sheetOf(
    lines.map(placed),
    rulings,
  )
}

/**
 * The pages as an Excel workbook, a sheet for each, named after its place in the list ("3쪽").
 *
 * @param all the whole page list, for the places
 */
export async function pagesToXlsx(pages: PageItem[], all: PageItem[]): Promise<Blob> {
  const sheets = []
  for (const page of pages) {
    sheets.push({ name: `${all.findIndex((p) => p.key === page.key) + 1}쪽`, sheet: await pageSheet(page) })
  }
  return sheetsToXlsx(sheets)
}
