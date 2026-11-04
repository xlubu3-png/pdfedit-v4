import { NUMBER } from './tableGrid'
import type { Sheet } from './tableGrid'

/**
 * An Excel workbook (.xlsx) made from sheets laid out as their pages are (see tableGrid's toSheet): column widths,
 * row heights, merged cells, borders, font sizes and alignment as on the page, which a paste cannot give (Excel
 * keeps its own column widths when something is pasted). Written here, without a library: a workbook is a few
 * XML files in a ZIP, stored uncompressed.
 */
export function sheetsToXlsx(sheets: { name: string; sheet: Sheet }[]): Blob {
  const styles = new Styles()
  const names = sheetNames(sheets.map((s) => s.name))
  const worksheets = sheets.map(({ sheet }) => worksheet(sheet, styles))
  const files: [string, string][] = [
    ['[Content_Types].xml', contentTypes(sheets.length)],
    ['_rels/.rels', ROOT_RELS],
    ['xl/workbook.xml', workbook(names)],
    ['xl/_rels/workbook.xml.rels', workbookRels(sheets.length)],
    ['xl/styles.xml', styles.xml()],
    ...worksheets.map((xml, i): [string, string] => [`xl/worksheets/sheet${i + 1}.xml`, xml]),
  ]
  return new Blob([zip(files)], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}

const MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
/** The font Korean Excel starts with; the page's sizes are kept. */
const FONT = '맑은 고딕'

const ROOT_RELS =
  XML_HEAD +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  `<Relationship Id="rId1" Type="${REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>`

function contentTypes(count: number): string {
  const sheets = Array.from(
    { length: count },
    (_, i) =>
      `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`,
  ).join('')
  return (
    XML_HEAD +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
    sheets +
    '</Types>'
  )
}

function workbook(names: string[]): string {
  const sheets = names.map((n, i) => `<sheet name="${escape(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')
  return XML_HEAD + `<workbook xmlns="${MAIN}" xmlns:r="${REL}"><sheets>${sheets}</sheets></workbook>`
}

function workbookRels(count: number): string {
  const sheets = Array.from(
    { length: count },
    (_, i) => `<Relationship Id="rId${i + 1}" Type="${REL}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`,
  ).join('')
  return (
    XML_HEAD +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    sheets +
    `<Relationship Id="rId${count + 1}" Type="${REL}/styles" Target="styles.xml"/></Relationships>`
  )
}

/** Sheet names as Excel takes them: at most 31 characters, none of \ / ? * [ ] :, no two alike. */
export function sheetNames(wanted: string[]): string[] {
  const used = new Set<string>()
  return wanted.map((name, i) => {
    const base = name.replace(/[\\/?*[\]:]/g, '_').replace(/^'+|'+$/g, '').trim().slice(0, 31) || `시트${i + 1}`
    let unique = base
    for (let n = 2; used.has(unique.toLowerCase()); n++) unique = `${base.slice(0, 31 - String(n).length - 1)}_${n}`
    used.add(unique.toLowerCase())
    return unique
  })
}

/** The look of a cell, as one entry of the workbook's style list. */
interface Look {
  size: number
  border: { top: boolean; right: boolean; bottom: boolean; left: boolean }
  align: 'left' | 'center' | 'right'
  wrap: boolean
  /** Built-in number format: 0 general, 3 "#,##0", 4 "#,##0.00". */
  numFmt: number
}

/** The workbook's fonts, borders and cell formats, each made once and shared. */
class Styles {
  private fonts: number[] = [11]
  private borders: string[] = ['0000']
  private xfs: string[] = ['']
  private looks = new Map<string, number>()

  index(look: Look): number {
    const key = JSON.stringify(look)
    const known = this.looks.get(key)
    if (known !== undefined) return known
    let font = this.fonts.indexOf(look.size)
    if (font < 0) font = this.fonts.push(look.size) - 1
    const b = look.border
    const borderKey = [b.left, b.right, b.top, b.bottom].map((x) => (x ? '1' : '0')).join('')
    let border = this.borders.indexOf(borderKey)
    if (border < 0) border = this.borders.push(borderKey) - 1
    const alignment = `<alignment horizontal="${look.align}" vertical="center"${look.wrap ? ' wrapText="1"' : ''}/>`
    const xf =
      `<xf numFmtId="${look.numFmt}" fontId="${font}" fillId="0" borderId="${border}" xfId="0" applyNumberFormat="1" ` +
      `applyFont="1" applyBorder="1" applyAlignment="1">${alignment}</xf>`
    const id = this.xfs.push(xf) - 1
    this.looks.set(key, id)
    return id
  }

  xml(): string {
    const fonts = this.fonts
      .map((size) => `<font><sz val="${size}"/><name val="${FONT}"/><family val="3"/><charset val="129"/></font>`)
      .join('')
    const side = (name: string, on: string) => (on === '1' ? `<${name} style="thin"><color auto="1"/></${name}>` : `<${name}/>`)
    const borders = this.borders
      .map((k) => `<border>${side('left', k[0])}${side('right', k[1])}${side('top', k[2])}${side('bottom', k[3])}<diagonal/></border>`)
      .join('')
    const xfs = this.xfs.map((xf) => xf || '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>').join('')
    return (
      XML_HEAD +
      `<styleSheet xmlns="${MAIN}">` +
      `<fonts count="${this.fonts.length}">${fonts}</fonts>` +
      '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>' +
      `<borders count="${this.borders.length}">${borders}</borders>` +
      '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
      `<cellXfs count="${this.xfs.length}">${xfs}</cellXfs>` +
      '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
      '</styleSheet>'
    )
  }
}

/** Column letters: 0 → A, 25 → Z, 26 → AA. */
export function columnName(index: number): string {
  let name = ''
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name
  return name
}

/** Points to Excel's column width unit (characters of the default font, about 7 pixels each, plus padding). */
const columnWidth = (pt: number) => Math.max(0.5, Math.round((((pt * 96) / 72 - 5) / 7) * 100) / 100)

function worksheet(sheet: Sheet, styles: Styles): string {
  // Every grid position a cell takes gets the cell's look, so a merged cell's borders are drawn all round it.
  const grid = new Map<number, Map<number, { style: number; value: string }>>()
  const put = (row: number, col: number, style: number, value: string) => {
    if (!grid.has(row)) grid.set(row, new Map())
    grid.get(row)!.set(col, { style, value })
  }
  const merges: string[] = []
  for (const cell of sheet.cells) {
    const r1 = Math.min(cell.row + cell.rowSpan, sheet.heights.length)
    const c1 = Math.min(cell.col + cell.colSpan, sheet.widths.length)
    const text = cell.lines.join('\n')
    const numeric = text !== '' && NUMBER.test(text)
    for (let r = cell.row; r < r1; r++) {
      for (let c = cell.col; c < c1; c++) {
        const b = cell.borders
        const style = styles.index({
          size: cell.size ?? 11,
          border: {
            top: !!b?.top && r === cell.row,
            bottom: !!b?.bottom && r === r1 - 1,
            left: !!b?.left && c === cell.col,
            right: !!b?.right && c === c1 - 1,
          },
          align: cell.align,
          // Text in a table wraps inside its cell; text outside the tables runs on over the empty cells beside it.
          wrap: cell.borders !== null || cell.lines.length > 1,
          numFmt: numeric && text.includes(',') ? (text.includes('.') ? 4 : 3) : 0,
        })
        put(r, c, style, r === cell.row && c === cell.col ? cellValue(text, numeric) : '/>')
      }
    }
    if (r1 - cell.row > 1 || c1 - cell.col > 1) {
      merges.push(`<mergeCell ref="${columnName(cell.col)}${cell.row + 1}:${columnName(c1 - 1)}${r1}"/>`)
    }
  }
  const cols = sheet.widths
    .map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${columnWidth(w)}" customWidth="1"/>`)
    .join('')
  const rows = sheet.heights
    .map((h, r) => {
      const cells = [...(grid.get(r) ?? new Map()).entries()]
        .sort((a, b) => a[0] - b[0])
        .map(([c, { style, value }]) => `<c r="${columnName(c)}${r + 1}" s="${style}"${value}`)
        .join('')
      return `<row r="${r + 1}" ht="${Math.max(1, Math.round(h * 4) / 4)}" customHeight="1">${cells}</row>`
    })
    .join('')
  return (
    XML_HEAD +
    `<worksheet xmlns="${MAIN}" xmlns:r="${REL}">` +
    // Printed as the page was: A4, the whole width on one sheet of paper.
    '<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>' +
    '<sheetFormatPr defaultRowHeight="16.5"/>' +
    (cols ? `<cols>${cols}</cols>` : '') +
    `<sheetData>${rows}</sheetData>` +
    (merges.length > 0 ? `<mergeCells count="${merges.length}">${merges.join('')}</mergeCells>` : '') +
    '<pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/>' +
    '<pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/>' +
    '</worksheet>'
  )
}

/** The rest of a `<c` element: a number as a number ("44,060" → 44060), anything else as text; empty when blank. */
function cellValue(text: string, numeric: boolean): string {
  if (text === '') return '/>'
  if (numeric) return `><v>${Number(text.replace(/,/g, ''))}</v></c>`
  return ` t="inlineStr"><is><t xml:space="preserve">${escape(text)}</t></is></c>`
}

/** Text as XML: the five special characters escaped, characters XML cannot hold left out. */
function escape(text: string): string {
  return text
    .replace(/[^\t\n\r -퟿-�\u{10000}-\u{10FFFF}]/gu, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

// ---- ZIP (stored, no compression) ----

const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

export function crc32(data: Uint8Array): number {
  let crc = 0xffffffff
  for (const byte of data) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

/** A ZIP of the files, stored as they are (Excel reads stored entries as well as compressed ones). */
export function zip(files: [string, string][]): Uint8Array<ArrayBuffer> {
  const encoder = new TextEncoder()
  const entries = files.map(([name, text]) => ({ name: encoder.encode(name), data: encoder.encode(text) }))
  const size = entries.reduce((n, e) => n + 30 + e.name.length + e.data.length + 46 + e.name.length, 0) + 22
  const out = new Uint8Array(new ArrayBuffer(size))
  const view = new DataView(out.buffer)
  let at = 0
  const central: { offset: number; crc: number; name: Uint8Array; length: number }[] = []
  for (const e of entries) {
    const crc = crc32(e.data)
    central.push({ offset: at, crc, name: e.name, length: e.data.length })
    view.setUint32(at, 0x04034b50, true)
    view.setUint16(at + 4, 20, true) // version needed
    view.setUint16(at + 6, 0x0800, true) // names in UTF-8
    view.setUint16(at + 8, 0, true) // stored
    view.setUint32(at + 10, 0x00210000, true) // 1980-01-01 00:00
    view.setUint32(at + 14, crc, true)
    view.setUint32(at + 18, e.data.length, true)
    view.setUint32(at + 22, e.data.length, true)
    view.setUint16(at + 26, e.name.length, true)
    view.setUint16(at + 28, 0, true)
    out.set(e.name, at + 30)
    out.set(e.data, at + 30 + e.name.length)
    at += 30 + e.name.length + e.data.length
  }
  const start = at
  for (const c of central) {
    view.setUint32(at, 0x02014b50, true)
    view.setUint16(at + 4, 20, true)
    view.setUint16(at + 6, 20, true)
    view.setUint16(at + 8, 0x0800, true)
    view.setUint16(at + 10, 0, true)
    view.setUint32(at + 12, 0x00210000, true)
    view.setUint32(at + 16, c.crc, true)
    view.setUint32(at + 20, c.length, true)
    view.setUint32(at + 24, c.length, true)
    view.setUint16(at + 28, c.name.length, true)
    view.setUint16(at + 30, 0, true) // extra
    view.setUint16(at + 32, 0, true) // comment
    view.setUint16(at + 34, 0, true) // disk
    view.setUint16(at + 36, 0, true) // internal attributes
    view.setUint32(at + 38, 0, true) // external attributes
    view.setUint32(at + 42, c.offset, true)
    out.set(c.name, at + 46)
    at += 46 + c.name.length
  }
  view.setUint32(at, 0x06054b50, true)
  view.setUint16(at + 4, 0, true)
  view.setUint16(at + 6, 0, true)
  view.setUint16(at + 8, central.length, true)
  view.setUint16(at + 10, central.length, true)
  view.setUint32(at + 12, at - start, true)
  view.setUint32(at + 16, start, true)
  view.setUint16(at + 20, 0, true)
  return out
}
