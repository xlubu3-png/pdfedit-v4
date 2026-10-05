import { describe, expect, it } from 'vitest'
import { keyCode } from './keys'

describe('keyCode', () => {
  it('uses the key position the browser reports', () => {
    expect(keyCode({ code: 'KeyC', key: 'ㅊ' })).toBe('KeyC')
    expect(keyCode({ code: 'BracketRight', key: ']' })).toBe('BracketRight')
  })

  it('falls back to the typed letter when there is no position', () => {
    expect(keyCode({ code: '', key: 'c' })).toBe('KeyC')
    expect(keyCode({ code: '', key: 'Z' })).toBe('KeyZ')
  })

  it('gives nothing for keys that are not letters', () => {
    expect(keyCode({ code: '', key: 'Escape' })).toBe('')
    expect(keyCode({ code: '', key: '5' })).toBe('')
    expect(keyCode({ code: '', key: 'ㅊ' })).toBe('')
  })
})
