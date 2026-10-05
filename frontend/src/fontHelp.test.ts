import { describe, expect, it } from 'vitest'
import { fontBaseName, fontSearchUrl, knownSource, missingFontNames, reloadMessage } from './fontHelp'
import type { TextRun } from './types'

describe('fontBaseName', () => {
  it('drops the subset tag and the style', () => {
    expect(fontBaseName('ABCDEF+HYwulM-Bold')).toBe('HYwulM')
    expect(fontBaseName('Arial,Bold')).toBe('Arial')
    expect(fontBaseName('HYwulM')).toBe('HYwulM')
  })
})

describe('fontSearchUrl', () => {
  it('searches for the base name', () => {
    const url = new URL(fontSearchUrl('ABCDEF+HYwulM-Bold'))
    expect(url.hostname).toBe('www.google.com')
    expect(url.searchParams.get('q')).toBe('HYwulM 글꼴 다운로드')
  })
})

describe('knownSource', () => {
  it('links the free families directly', () => {
    expect(knownSource('XYZABC+NanumGothic')?.url).toBe('https://hangeul.naver.com/font')
    expect(knownSource('NotoSansKR-Regular')?.url).toContain('fonts.google.com')
  })

  it('only explains where Hancom and Windows fonts come from', () => {
    expect(knownSource('ABCDEF+HYwulM')?.url).toBeUndefined()
    expect(knownSource('ABCDEF+HYwulM')?.label).toBe('한컴 글꼴')
    expect(knownSource('ABCDEF+MalgunGothic')?.label).toBe('Windows 기본 글꼴')
  })

  it('knows nothing about other fonts', () => {
    expect(knownSource('ABCDEF+SomethingElse')).toBeNull()
  })
})

describe('missingFontNames', () => {
  const run = (sourceFont: string, sourceFontMissing: boolean) => ({ sourceFont, sourceFontMissing }) as TextRun

  it('lists each missing font once, in order', () => {
    expect(missingFontNames([run('B', true), run('A', false), run('C', true), run('B', true)])).toEqual(['B', 'C'])
  })
})

describe('reloadMessage', () => {
  it('says how the count changed', () => {
    expect(reloadMessage(261, 263)).toContain('새로 2개')
    expect(reloadMessage(261, 261)).toContain('찾지 못했습니다')
    expect(reloadMessage(261, 250)).toContain('줄었습니다')
    expect(reloadMessage(undefined, 5)).toBe('글꼴 5개를 읽었습니다.')
  })
})
