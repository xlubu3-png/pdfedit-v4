import { describe, expect, it } from 'vitest'
import { errorText, isKorean } from './errors'

describe('errorText', () => {
  it("shows the app's own Korean message", () => {
    expect(errorText(new Error('암호가 맞지 않습니다.'), '열지 못했습니다')).toBe('암호가 맞지 않습니다.')
  })

  it("replaces a browser's or library's English with the Korean fallback", () => {
    expect(errorText(new TypeError('Failed to fetch'), '저장하지 못했습니다')).toBe('저장하지 못했습니다')
    expect(errorText(new SyntaxError("Unexpected token '<'"), '불러오지 못했습니다')).toBe('불러오지 못했습니다')
    expect(errorText('boom', '실패했습니다')).toBe('실패했습니다')
    expect(errorText(undefined, '실패했습니다')).toBe('실패했습니다')
  })

  it('tells Korean text', () => {
    expect(isKorean('계약서.pdf: 파일이 너무 큽니다')).toBe(true)
    expect(isKorean('{"status":500,"error":"Internal Server Error"}')).toBe(false)
  })
})
