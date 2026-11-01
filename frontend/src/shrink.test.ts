import { describe, expect, it } from 'vitest'
import { parseShrinkReport, shrinkMessage, sizeText } from './shrink'

describe('shrink report', () => {
  it('reads the header', () => {
    expect(parseShrinkReport('pictures=12;before=24000000;after=3100000')).toEqual({
      pictures: 12,
      before: 24000000,
      after: 3100000,
    })
    expect(parseShrinkReport(null)).toBeNull()
    expect(parseShrinkReport('pictures=x;before=1;after=1')).toBeNull()
    expect(parseShrinkReport('pictures=1')).toBeNull()
  })

  it('says sizes as people do', () => {
    expect(sizeText(512)).toBe('1KB')
    expect(sizeText(850 * 1024)).toBe('850KB')
    expect(sizeText(12.4 * 1024 * 1024)).toBe('12.4MB')
  })

  it('says what was saved, or that nothing could be', () => {
    const mb = 1024 * 1024
    expect(shrinkMessage({ pictures: 12, before: 24 * mb, after: 3 * mb }, 4 * mb, false)).toBe(
      '용량 줄이기: 그림 12개를 다시 저장해 21.0MB를 줄였습니다 (파일 4.0MB).',
    )
    expect(shrinkMessage({ pictures: 0, before: 0, after: 0 }, 300 * 1024, false)).toContain('더 줄일 그림이 없었습니다')
    expect(shrinkMessage(null, 2 * mb, true)).toContain('페이지를 그림으로 바꾸면서')
  })
})
