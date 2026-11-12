import { NUMBER } from './tableGrid'
import type { Sheet, SheetCell } from './tableGrid'

/**
 * The sheets the app makes from a PDF, as the workbook the 표 다듬기 screen (Univer) works on, and back again:
 * the app hands over its own sheets and writes the xlsx itself from what comes back, so nothing is lost to a
 * file format on the way. Only plain data crosses here; the screen itself is loaded when it is opened.
 */

/** A cell's look in the workbook (Univer's style names: bl bold, fs size, ht/vt alignment, tb wrap, bd borders). */
interface BookStyle {
  bl?: number
  fs?: number
  ht?: number
  vt?: number
  tb?: number
  bg?: { rgb?: string | null } | null
  bd?: Partial<Record<'t' | 'b' | 'l' | 'r', { s?: number } | null>> | null
  n?: { pattern?: string } | null
}

interface BookCell {
  v?: string | number | boolean | null
  f?: string | null
  s?: string | BookStyle | null
  p?: { body?: { dataStream?: string } } | null
}

interface BookSheet {
  id: string
  name: string
  rowCount: number
  columnCount: number
  defaultColumnWidth?: number
  defaultRowHeight?: number
  cellData: Record<number, Record<number, BookCell>>
  mergeData: { startRow: number; endRow: number; startColumn: number; endColumn: number }[]
  columnData: Record<number, { w?: number; hd?: number }>
  rowData: Record<number, { h?: number; ah?: number; hd?: number }>
}

export interface Book {
  id: string
  name: string
  appVersion: string
  locale: string
  sheetOrder: string[]
  styles: Record<string, BookStyle>
  sheets: Record<string, BookSheet>
}

const PX_PER_PT = 96 / 72
const DEFAULT_COLUMN_PX = 88
const DEFAULT_ROW_PX = 24
/** Rows and columns left free after the table's own, to put more in. */
const SPARE_ROWS = 30
const SPARE_COLUMNS = 6
const LINE = { s: 1, cl: { rgb: '#000000' } }

/** The sheets as a workbook for the screen. */
export function toBook(sheets: { name: string; sheet: Sheet }[], name: string): Book {
  const book: Book = { id: 'polish', name, appVersion: '1', locale: 'koKR', sheetOrder: [], styles: {}, sheets: {} }
  sheets.forEach(({ name: sheetName, sheet }, index) => {
    const id = `s${index + 1}`
    const cellData: BookSheet['cellData'] = {}
    const mergeData: BookSheet['mergeData'] = []
    const put = (r: number, c: number, cell: BookCell) => {
      cellData[r] ??= {}
      cellData[r][c] = cell
    }
    for (const cell of sheet.cells) {
      const r1 = cell.row + Math.max(1, cell.rowSpan)
      const c1 = cell.col + Math.max(1, cell.colSpan)
      const text = cell.lines.join('\n')
      const numeric = text !== '' && NUMBER.test(text)
      for (let r = cell.row; r < r1; r++) {
        for (let c = cell.col; c < c1; c++) {
          const b = cell.borders
          const bd: NonNullable<BookStyle['bd']> = {}
          if (b?.top && r === cell.row) bd.t = LINE
          if (b?.bottom && r === r1 - 1) bd.b = LINE
          if (b?.left && c === cell.col) bd.l = LINE
          if (b?.right && c === c1 - 1) bd.r = LINE
          const style: BookStyle = {
            fs: cell.size ?? 11,
            ht: cell.align === 'center' ? 2 : cell.align === 'right' ? 3 : 1,
            vt: 2,
            ...(cell.borders !== null || cell.lines.length > 1 ? { tb: 3 } : {}),
            ...(Object.keys(bd).length > 0 ? { bd } : {}),
            ...(cell.bold ? { bl: 1 } : {}),
            ...(cell.fill ? { bg: { rgb: `#${cell.fill}` } } : {}),
            ...(numeric && text.includes(',') ? { n: { pattern: text.includes('.') ? '#,##0.00' : '#,##0' } } : {}),
          }
          const first = r === cell.row && c === cell.col
          put(r, c, {
            ...(first && text !== '' ? { v: numeric ? Number(text.replace(/,/g, '')) : text } : {}),
            ...(first && cell.formula ? { f: `=${cell.formula}` } : {}),
            s: style,
          })
        }
      }
      if (r1 - cell.row > 1 || c1 - cell.col > 1) {
        mergeData.push({ startRow: cell.row, endRow: r1 - 1, startColumn: cell.col, endColumn: c1 - 1 })
      }
    }
    book.sheetOrder.push(id)
    book.sheets[id] = {
      id,
      name: sheetName,
      rowCount: sheet.heights.length + SPARE_ROWS,
      columnCount: sheet.widths.length + SPARE_COLUMNS,
      cellData,
      mergeData,
      columnData: Object.fromEntries(sheet.widths.map((w, i) => [i, { w: Math.max(8, Math.round(w * PX_PER_PT * 1000) / 1000) }])),
      rowData: Object.fromEntries(sheet.heights.map((h, i) => [i, { h: Math.max(8, Math.round(h * PX_PER_PT * 1000) / 1000) }])),
    }
  })
  return book
}

/** A number as the sheet writes it: with thousands separators when its cell shows them. */
function numberText(value: number, pattern: string | undefined): string {
  if (!Number.isFinite(value)) return ''
  if (!pattern || !pattern.includes(',')) return String(value)
  const decimals = /\.(0+)/.exec(pattern)?.[1].length ?? 0
  return value.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
}

/** The workbook, as the screen gives it back, as sheets for the app's xlsx writer. */
export function fromBook(book: Book): { name: string; sheet: Sheet }[] {
  const styleOf = (s: BookCell['s']): BookStyle => (typeof s === 'string' ? (book.styles?.[s] ?? {}) : (s ?? {}))
  return book.sheetOrder.flatMap((id) => {
    const from = book.sheets[id]
    if (!from) return []
    const cellData = from.cellData ?? {}
    const merges = from.mergeData ?? []
    const covered = new Set<string>()
    const spans = new Map<string, (typeof merges)[number]>()
    for (const m of merges) {
      spans.set(`${m.startRow},${m.startColumn}`, m)
      for (let r = m.startRow; r <= m.endRow; r++) {
        for (let c = m.startColumn; c <= m.endColumn; c++) {
          if (r !== m.startRow || c !== m.startColumn) covered.add(`${r},${c}`)
        }
      }
    }
    const cells: SheetCell[] = []
    let rows = 0
    let columns = 0
    for (const [rk, row] of Object.entries(cellData)) {
      for (const [ck, cell] of Object.entries(row ?? {})) {
        const r = Number(rk)
        const c = Number(ck)
        if (!cell || covered.has(`${r},${c}`)) continue
        const style = styleOf(cell.s)
        const m = spans.get(`${r},${c}`)
        // A merged cell's far sides are drawn by the cells at its far corner.
        const far = m ? styleOf(cellData[m.endRow]?.[m.endColumn]?.s) : style
        const raw = cell.p?.body?.dataStream?.replace(/\r\n$/, '').replace(/\r/g, '\n') ?? cell.v
        const text =
          typeof raw === 'number' ? numberText(raw, style.n?.pattern) : raw === null || raw === undefined ? '' : String(raw)
        const on = (side: { s?: number } | null | undefined) => !!side && (side.s ?? 0) !== 0
        const borders = {
          top: on(style.bd?.t),
          left: on(style.bd?.l),
          bottom: on(far.bd?.b),
          right: on(far.bd?.r),
        }
        const drawn = borders.top || borders.left || borders.bottom || borders.right
        const fill = /^#?([0-9a-fA-F]{6})$/.exec(style.bg?.rgb ?? '')?.[1]
        const formula = typeof cell.f === 'string' && cell.f.startsWith('=') ? cell.f.slice(1) : undefined
        if (text === '' && !drawn && !fill && !formula && !m) continue
        cells.push({
          row: r,
          col: c,
          rowSpan: m ? m.endRow - m.startRow + 1 : 1,
          colSpan: m ? m.endColumn - m.startColumn + 1 : 1,
          lines: text === '' ? [] : text.split('\n'),
          borders: drawn ? borders : null,
          align: style.ht === 2 ? 'center' : style.ht === 3 ? 'right' : style.ht === 1 ? 'left' : typeof raw === 'number' ? 'right' : 'left',
          size: style.fs ?? 11,
          ...(formula ? { formula } : {}),
          ...(style.bl ? { bold: true } : {}),
          ...(fill ? { fill: fill.toUpperCase() } : {}),
        })
        rows = Math.max(rows, (m ? m.endRow : r) + 1)
        columns = Math.max(columns, (m ? m.endColumn : c) + 1)
      }
    }
    cells.sort((a, b) => a.row - b.row || a.col - b.col)
    const width = (i: number) => (from.columnData?.[i]?.w ?? from.defaultColumnWidth ?? DEFAULT_COLUMN_PX) / PX_PER_PT
    const height = (i: number) => {
      const row = from.rowData?.[i]
      return (row?.h ?? row?.ah ?? from.defaultRowHeight ?? DEFAULT_ROW_PX) / PX_PER_PT
    }
    return [
      {
        name: from.name,
        sheet: {
          widths: Array.from({ length: columns }, (_, i) => Math.round(width(i) * 100) / 100),
          heights: Array.from({ length: rows }, (_, i) => Math.round(height(i) * 100) / 100),
          cells,
        },
      },
    ]
  })
}
