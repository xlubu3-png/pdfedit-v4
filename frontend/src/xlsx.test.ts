import { describe, expect, it } from 'vitest'
import type { Sheet } from './tableGrid'
import { columnName, crc32, sheetNames, sheetsToXlsx } from './xlsx'

/** The files of a ZIP whose entries are stored (as sheetsToXlsx writes them). */
async function unzip(blob: Blob): Promise<Map<string, string>> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const view = new DataView(bytes.buffer)
  const files = new Map<string, string>()
  const decoder = new TextDecoder()
  for (let at = 0; view.getUint32(at, true) === 0x04034b50; ) {
    const size = view.getUint32(at + 18, true)
    const nameLength = view.getUint16(at + 26, true)
    const name = decoder.decode(bytes.subarray(at + 30, at + 30 + nameLength))
    const data = bytes.subarray(at + 30 + nameLength, at + 30 + nameLength + size)
    expect(crc32(data)).toBe(view.getUint32(at + 14, true))
    files.set(name, decoder.decode(data))
    at += 30 + nameLength + size
  }
  return files
}

const sheet: Sheet = {
  widths: [30, 60, 70],
  heights: [20, 20],
  cells: [
    { row: 0, col: 0, rowSpan: 1, colSpan: 2, lines: ['품명'], borders: { top: true, right: true, bottom: true, left: true }, align: 'center', size: 9 },
    { row: 0, col: 2, rowSpan: 1, colSpan: 1, lines: ['44,060'], borders: { top: true, right: true, bottom: true, left: true }, align: 'right', size: 9 },
    { row: 1, col: 0, rowSpan: 1, colSpan: 1, lines: ['1-A & <B>'], borders: null, align: 'left', size: 12 },
  ],
}

describe('sheetsToXlsx', () => {
  it('writes a workbook Excel opens: the parts, a sheet per page, merged cells, numbers as numbers', async () => {
    const files = await unzip(sheetsToXlsx([{ name: '1쪽', sheet }, { name: '2쪽', sheet }]))

    expect([...files.keys()]).toEqual([
      '[Content_Types].xml',
      '_rels/.rels',
      'xl/workbook.xml',
      'xl/_rels/workbook.xml.rels',
      'xl/styles.xml',
      'xl/worksheets/sheet1.xml',
      'xl/worksheets/sheet2.xml',
    ])
    expect(files.get('xl/workbook.xml')).toContain('<sheet name="2쪽" sheetId="2" r:id="rId2"/>')
    const xml = files.get('xl/worksheets/sheet1.xml')!
    expect(xml).toContain('<mergeCell ref="A1:B1"/>')
    expect(xml).toMatch(/<c r="C1" s="\d+"><v>44060<\/v><\/c>/)
    expect(xml).toContain('<t xml:space="preserve">1-A &amp; &lt;B&gt;</t>')
    expect(xml).toContain('<col min="1" max="1" width="')
    expect(xml).toContain('<row r="1" ht="20" customHeight="1">')
    // The merged cell's second position carries its borders too, so they are drawn all round it.
    expect(xml).toMatch(/<c r="B1" s="\d+"\/>/)
    const styles = files.get('xl/styles.xml')!
    expect(styles).toContain('<sz val="9"/>')
    expect(styles).toContain('numFmtId="3"')
    expect(styles).toContain('<top style="thin">')
  })
})

describe('helpers', () => {
  it('checks data as ZIP does', () => {
    expect(crc32(new TextEncoder().encode('hello'))).toBe(0x3610a686)
  })

  it('names columns as Excel does', () => {
    expect([0, 25, 26, 51, 52, 701, 702].map(columnName)).toEqual(['A', 'Z', 'AA', 'AZ', 'BA', 'ZZ', 'AAA'])
  })

  it('gives sheet names Excel takes', () => {
    expect(sheetNames(['1쪽', '1쪽', 'a/b:c', '', 'x'.repeat(40)])).toEqual(['1쪽', '1쪽_2', 'a_b_c', '시트4', 'x'.repeat(31)])
  })
})
