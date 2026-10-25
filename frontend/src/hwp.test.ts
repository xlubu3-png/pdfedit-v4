import { describe, expect, it } from 'vitest'
import { formatOf, savedName } from './hwp'

describe('Hangul document helpers', () => {
  it('keeps a document in the format it came in', () => {
    expect(formatOf('계획서.hwp')).toBe('hwp')
    expect(formatOf('계획서.HWPX')).toBe('hwpx')
  })

  it('names a saved copy after the document, with the extension of the format', () => {
    expect(savedName('계획서.hwp', 'hwpx')).toBe('계획서.hwpx')
    expect(savedName('보고서.final.HWPX', 'hwp')).toBe('보고서.final.hwp')
    expect(savedName('.hwp', 'hwp')).toBe('문서.hwp')
  })
})
