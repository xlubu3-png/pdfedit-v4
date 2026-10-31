import { describe, expect, it, vi } from 'vitest'
import { ocrMessage, recognizeAll } from './ocr'
import type { OcrResult, PageItem } from './types'

const page = (key: string): PageItem => ({
  key,
  documentId: 'doc-' + key,
  sourceFileName: 'scan.pdf',
  pageIndex: 0,
  rotation: 0,
  textRevision: 0,
})

const result = (lines: number, alreadyText = 0): OcrResult => ({
  lines: Array.from({ length: lines }, (_, i) => ({ text: `줄 ${i}`, x: 0, y: 0, width: 10, height: 10, score: 1 })),
  alreadyText,
  seconds: 1,
})

describe('recognizeAll', () => {
  it('reads the pages one after another and reports how far it got', async () => {
    const recognize = vi.fn().mockResolvedValueOnce(result(3)).mockResolvedValueOnce(result(0, 4))
    const progress = vi.fn()

    const summary = await recognizeAll([page('a'), page('b')], recognize, progress, () => false)

    expect(recognize).toHaveBeenNthCalledWith(1, 'doc-a', 0)
    expect(progress).toHaveBeenCalledWith(0, 2)
    expect(progress).toHaveBeenCalledWith(2, 2)
    expect(summary).toEqual({ pages: 2, lines: 3, linesByKey: { a: 3, b: 0 }, alreadyText: 1, stopped: false })
  })

  it('stops before the next page when asked to', async () => {
    let stop = false
    const recognize = vi.fn().mockImplementation(async () => {
      stop = true
      return result(2)
    })

    const summary = await recognizeAll([page('a'), page('b')], recognize, () => {}, () => stop)

    expect(recognize).toHaveBeenCalledTimes(1)
    expect(summary).toMatchObject({ pages: 1, lines: 2, stopped: true })
  })
})

describe('ocrMessage', () => {
  it('says what was recognized and what it is good for', () => {
    expect(ocrMessage({ pages: 3, lines: 120, linesByKey: {}, alreadyText: 0, stopped: false })).toBe(
      '3쪽에서 글자 120줄을 인식했습니다. 내보낸 PDF에서 글자를 검색·복사할 수 있습니다.',
    )
  })

  it('mentions pages that already had text, nothing found, and a stop', () => {
    expect(ocrMessage({ pages: 2, lines: 0, linesByKey: {}, alreadyText: 2, stopped: false })).toBe(
      '인식할 글자가 없었습니다. 2쪽은 이미 글자가 들어 있는 PDF입니다.',
    )
    expect(ocrMessage({ pages: 1, lines: 5, linesByKey: {}, alreadyText: 0, stopped: true })).toContain('중지했습니다')
  })
})
