import { describe, expect, it } from 'vitest'
import { imageExportFileName, imageExportStem } from './imageExport'

const page = (key: string, name = '계약서.pdf') => ({ key, documentId: 'doc-1', sourceFileName: name })

describe('imageExportStem', () => {
  it('names the pictures after the export', () => {
    const all = [page('a'), page('b')]
    expect(imageExportStem(all, all)).toBe('계약서_수정본')
  })

  it('says which page one page picked out of several is', () => {
    const all = [page('a'), page('b'), page('c')]
    expect(imageExportStem([all[2]], all)).toBe('계약서_수정본_3쪽')
    expect(imageExportStem([all[0]], [all[0]])).toBe('계약서_수정본')
  })
})

describe('imageExportFileName', () => {
  it('is a picture for one page and a ZIP for more', () => {
    expect(imageExportFileName('계약서_수정본', 1, 'jpg')).toBe('계약서_수정본.jpg')
    expect(imageExportFileName('계약서_수정본', 1, 'png')).toBe('계약서_수정본.png')
    expect(imageExportFileName('계약서_수정본', 4, 'png')).toBe('계약서_수정본.zip')
  })
})
