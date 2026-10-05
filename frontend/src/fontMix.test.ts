import { describe, expect, it } from 'vitest'
import { foreignChars } from './fontMix'

describe('foreignChars', () => {
  it('lists typed characters the page font does not draw, once each', () => {
    expect(foreignChars('제5장 부 록가나다가', '제5장부록')).toBe('가나다')
  })

  it('is empty when every character comes from the page font', () => {
    expect(foreignChars('부 록 5', '제5장부록')).toBe('')
  })

  it('ignores spaces and line breaks', () => {
    expect(foreignChars(' \n\t', '')).toBe('')
  })
})
