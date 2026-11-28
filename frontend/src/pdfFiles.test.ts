import { describe, expect, it } from 'vitest'
import { dragHasFiles, pickPdfs } from './pdfFiles'

const file = (name: string, type: string) => new File(['x'], name, { type })

describe('pickPdfs', () => {
  it('takes PDFs by type or by name, and Office documents to be printed to PDF, and reports the others', () => {
    const { pdfs, skipped } = pickPdfs([
      file('a.pdf', 'application/pdf'),
      file('b.PDF', ''),
      file('c.docx', 'application/msword'),
      file('견적서.xlsx', ''),
      file('발표.PPTX', ''),
      file('d.zip', 'application/zip'),
    ])

    expect(pdfs.map((f) => f.name)).toEqual(['a.pdf', 'b.PDF', 'c.docx', '견적서.xlsx', '발표.PPTX'])
    expect(skipped).toEqual(['d.zip'])
  })

  it('sets Hangul documents apart for the HWP editor', () => {
    const { pdfs, hwps, skipped } = pickPdfs([file('a.hwp', ''), file('b.HWPX', ''), file('c.pdf', ''), file('d.hwt', '')])

    expect(hwps.map((f) => f.name)).toEqual(['a.hwp', 'b.HWPX'])
    expect(pdfs.map((f) => f.name)).toEqual(['c.pdf'])
    expect(skipped).toEqual(['d.hwt'])
  })
})

describe('dragHasFiles', () => {
  it('is true only when files are dragged in', () => {
    expect(dragHasFiles(['Files'])).toBe(true)
    expect(dragHasFiles(['text/plain'])).toBe(false)
    expect(dragHasFiles(undefined)).toBe(false)
  })
})

describe('pictures', () => {
  it('opens photos, scans and faxes saved as pictures like PDFs', () => {
    const file = (name: string) => new File([''], name)
    const { pdfs, skipped } = pickPdfs([file('팩스.TIF'), file('사진.jpeg'), file('화면.png'), file('메모.txt')])
    expect(pdfs.map((f) => f.name)).toEqual(['팩스.TIF', '사진.jpeg', '화면.png'])
    expect(skipped).toEqual(['메모.txt'])
  })
})

