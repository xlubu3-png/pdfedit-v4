import { fetchPageOcr, fetchPageText, fetchRulings, fetchTableGrids } from './api'
import { findTables, layout, straighten, toSheet, turnOf, withGrids } from './tableGrid'
import type { PlacedText } from './textSelect'
import type { Grid, Sheet, Table } from './tableGrid'
import { placed, rewrittenLines, selectableLines } from './textSelect'
import type { PageItem } from './types'
import { joinedTables } from './joinTables'
import { sheetsToXlsx } from './xlsx'

const EMPTY: Sheet = { widths: [], heights: [], cells: [] }

/**
 * The page's text as one sheet laid out as the page is: its tables in their cells, the rest where it stands. Tables
 * set out by hand (`grids`, as the page is seen) take the place of those found where they lie.
 */
export function sheetOf(texts: PlacedText[], rulings: Parameters<typeof findTables>[0], grids: Grid[] = []): Sheet {
  if (grids.length > 0) return sheetWith(texts, withGrids(findTables(rulings), grids))
  return sheetWith(straighten(texts, turnOf(rulings)), findTables(rulings))
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
async function pageSheet(page: PageItem): Promise<{ sheet: Sheet; tables: Table[] }> {
  const [data, rulings, ocr, grids] = await Promise.all([
    fetchPageText(page.documentId, page.pageIndex),
    fetchRulings(page.documentId, page.pageIndex).catch(() => ({ horizontal: [], vertical: [] })),
    fetchPageOcr(page.documentId, page.pageIndex).catch(() => ({ lines: [] })),
    fetchTableGrids(page.documentId, page.pageIndex).catch(() => [] as Grid[]),
  ])
  const lines = selectableLines(data, new Map(), ocr.lines, rewrittenLines(ocr.lines, data.added ?? []), data.added ?? [], 1)
  const texts = lines.map(placed)
  const blocks =
    grids.length > 0
      ? layout(texts, withGrids(findTables(rulings), grids), false)
      : layout(straighten(texts, turnOf(rulings)), findTables(rulings), false)
  return {
    sheet: blocks.length > 0 ? toSheet(blocks) : EMPTY,
    tables: blocks.flatMap((b) => (b.kind === 'table' ? [b.table] : [])),
  }
}

/**
 * The pages as an Excel workbook, a sheet for each, named after its place in the list ("3쪽"). A table running on
 * over pages (a quote's items over two pages) comes first as a sheet of its own, joined into one ("이어진 표
 * 1~2쪽"), its heading once.
 *
 * @param all the whole page list, for the places
 */
export async function pagesToXlsx(pages: PageItem[], all: PageItem[]): Promise<Blob> {
  return (await pagesToWorkbook(pages, all)).blob
}

/** {@link pagesToXlsx}, saying how many tables were joined over pages. */
export async function pagesToWorkbook(pages: PageItem[], all: PageItem[]): Promise<{ blob: Blob; joined: number }> {
  const sheets = []
  const tables: Table[][] = []
  const places = pages.map((page) => all.findIndex((p) => p.key === page.key) + 1)
  for (const [i, page] of pages.entries()) {
    const { sheet, tables: found } = await pageSheet(page)
    sheets.push({ name: `${places[i]}쪽`, sheet })
    tables.push(found)
  }
  const joined = joinedTables(tables).map((j) => ({
    name: `이어진 표 ${places[j.pages[0]]}~${places[j.pages[j.pages.length - 1]]}쪽`,
    sheet: toSheet([{ kind: 'table' as const, table: j.table, top: 0 }]),
  }))
  return { blob: sheetsToXlsx([...joined, ...sheets]), joined: joined.length }
}
