import { describe, expect, it } from 'vitest'
import {
  familyList,
  fontBaseName,
  fontSearchUrl,
  knownSource,
  missingFontNames,
  otherFormatFamily,
  reloadMessage,
} from './fontHelp'
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

describe('fonts installed only as .otf', () => {
  it('finds the family a font of the page stands for, however the PDF spells it', () => {
    expect(otherFormatFamily('ABCDEF+Inter-Bold', ['Inter'])).toBe('Inter')
    expect(otherFormatFamily('NotoSansCJKkr-Regular', ['Noto Sans CJK KR'])).toBe('Noto Sans CJK KR')
    expect(otherFormatFamily('Pretendard-Medium', ['Source Han Sans', 'Pretendard'])).toBe('Pretendard')
  })

  it('does not mistake one font for another, or match on a few letters', () => {
    expect(otherFormatFamily('Batang', ['Inter'])).toBeNull()
    expect(otherFormatFamily('HY', ['HYGothic'])).toBeNull() // "hy" is too short to mean anything
    expect(otherFormatFamily('Inter', [])).toBeNull()
  })

  it('lists the first few families and counts the rest', () => {
    expect(familyList(['A', 'B'])).toBe('A, B')
    expect(familyList(['A', 'B', 'C', 'D', 'E'])).toBe('A, B, C 외 2개')
    expect(familyList(['A', 'B', 'C', 'D'], 2)).toBe('A, B 외 2개')
  })

  it('names them when a reload finds nothing new, which is when they are the likely reason', () => {
    expect(reloadMessage(261, 261, ['Inter'])).toContain('.otf')
    expect(reloadMessage(261, 261, ['Inter'])).toContain('Inter')
    expect(reloadMessage(261, 261)).not.toContain('.otf')
    expect(reloadMessage(261, 263, ['Inter'])).not.toContain('.otf') // a new font was found: nothing to explain
  })
})
