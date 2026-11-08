import { describe, expect, it } from 'vitest'
import { batchZipName, filesOf, outputName } from './batch'
import type { PageItem } from './types'

const page = (documentId: string, sourceFileName: string, pageIndex: number): PageItem => ({
  key: `${documentId}-${pageIndex}`,
  documentId,
  sourceFileName,
  pageIndex,
  rotation: 0,
  textRevision: 0,
})

describe('파일마다 처리', () => {
  it('groups the pages by the file they came from, in the order the files first appear', () => {
    const pages = [page('a', '견적서.pdf', 0), page('b', '등록증.pdf', 0), page('a', '견적서.pdf', 1)]
    expect(filesOf(pages).map((f) => [f.name, f.pages.length])).toEqual([
      ['견적서.pdf', 2],
      ['등록증.pdf', 1],
    ])
  })

  it('names each result after its file, unique in the ZIP', () => {
    const taken = new Set<string>()
    expect(outputName('견적서.pdf', 'pdf', taken)).toBe('견적서.pdf')
    expect(outputName('견적서.PDF', 'pdf', taken)).toBe('견적서 (2).pdf')
    expect(outputName('견적서.pdf', 'xlsx', taken)).toBe('견적서.xlsx')
    expect(outputName('a/b:c.pdf', 'pdf', taken)).toBe('a_b_c.pdf')
  })

  it('dates the ZIP', () => {
    expect(batchZipName(new Date(2026, 10, 8, 9, 5))).toBe('일괄처리_20261108_0905.zip')
  })
})
