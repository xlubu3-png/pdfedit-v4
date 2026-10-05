import { describe, expect, it } from 'vitest'
import { dragHasFiles, pickPdfs } from './pdfFiles'

const file = (name: string, type: string) => new File(['x'], name, { type })

describe('pickPdfs', () => {
  it('takes PDFs by type or by name and reports the others', () => {
    const { pdfs, skipped } = pickPdfs([
      file('a.pdf', 'application/pdf'),
      file('b.PDF', ''),
      file('c.docx', 'application/msword'),
      file('d.png', 'image/png'),
    ])

    expect(pdfs.map((f) => f.name)).toEqual(['a.pdf', 'b.PDF'])
    expect(skipped).toEqual(['c.docx', 'd.png'])
  })
})

describe('dragHasFiles', () => {
  it('is true only when files are dragged in', () => {
    expect(dragHasFiles(['Files'])).toBe(true)
    expect(dragHasFiles(['text/plain'])).toBe(false)
    expect(dragHasFiles(undefined)).toBe(false)
  })
})
