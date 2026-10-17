import type { TextRun } from './types'

/** Where a font that the page uses can usually be had. */
export interface FontSource {
  /** What the link is called. */
  label: string
  url?: string
  /** What to know before looking for it. */
  note: string
}

/** "ABCDEF+HYwulM-Bold" is the font HYwulM: without the subset tag and the style. */
export function fontBaseName(pdfName: string): string {
  const withoutTag = pdfName.replace(/^[A-Z]{6}\+/, '')
  const style = withoutTag.search(/[-,]/)
  return (style > 0 ? withoutTag.slice(0, style) : withoutTag).trim()
}

/** A web search for the font, since fonts have owners and no one address. */
export function fontSearchUrl(pdfName: string): string {
  return `https://www.google.com/search?q=${encodeURIComponent(`${fontBaseName(pdfName)} 글꼴 다운로드`)}`
}

/**
 * What is known about getting this font. Only well-known free families get a direct link; for the rest
 * (HY, Hancom, Windows fonts) all that can be said is where they usually come from.
 */
export function knownSource(pdfName: string): FontSource | null {
  const name = fontBaseName(pdfName)
  if (/nanum|나눔/i.test(name)) {
    return { label: '네이버 나눔글꼴', url: 'https://hangeul.naver.com/font', note: '무료로 배포되는 글꼴입니다.' }
  }
  if (/noto/i.test(name)) {
    return { label: 'Google Fonts - Noto', url: 'https://fonts.google.com/noto', note: '무료로 배포되는 글꼴입니다.' }
  }
  if (/pretendard/i.test(name)) {
    return { label: 'Pretendard (GitHub)', url: 'https://github.com/orioncactus/pretendard', note: '무료로 배포되는 글꼴입니다.' }
  }
  if (/^(hy|hcr)|한컴|hancom|휴먼|함초롬/i.test(name)) {
    return {
      label: '한컴 글꼴',
      note: '한컴오피스(한글)를 설치하면 함께 설치되는 글꼴이 많습니다. 한컴오피스가 있는 PC에서 글꼴 파일을 복사해 올 수도 있습니다.',
    }
  }
  if (/malgun|맑은|batang|gungsuh|dotum|gulim|바탕|궁서|돋움|굴림/i.test(name)) {
    return {
      label: 'Windows 기본 글꼴',
      note: 'Windows에 들어 있는 글꼴입니다. 이 PC에 없다면 Windows 설정의 선택적 기능에서 글꼴을 추가하거나 Windows를 업데이트해 보세요.',
    }
  }
  return null
}

/** The named fonts the page uses that are not installed on this PC, each once, in order of appearance. */
export function missingFontNames(runs: TextRun[]): string[] {
  const names: string[] = []
  for (const run of runs) {
    if (run.sourceFontMissing && !names.includes(run.sourceFont)) names.push(run.sourceFont)
  }
  return names
}

const squash = (name: string) => name.toLowerCase().replace(/[\s\-_]/g, '')

/**
 * The family, among those installed only as .otf files (which this app cannot read), that a font of the page
 * stands for - "ABCDEF+Inter-Bold" is the family "Inter" - or null. Compared without spaces, dashes or case, and
 * either name may contain the other ("Noto Sans CJK KR" and "NotoSansCJKkr-Regular").
 */
export function otherFormatFamily(pdfName: string, unreadableFamilies: string[]): string | null {
  const wanted = squash(fontBaseName(pdfName))
  if (wanted.length < 3) return null
  return (
    unreadableFamilies.find((family) => {
      const have = squash(family)
      return have.length >= 3 && (have === wanted || wanted.includes(have) || have.includes(wanted))
    }) ?? null
  )
}

/** "Inter, Noto Sans CJK KR" - the first few families, then how many more. */
export function familyList(families: string[], shown = 3): string {
  const head = families.slice(0, shown).join(', ')
  return families.length > shown ? `${head} 외 ${families.length - shown}개` : head
}

/**
 * What to tell the user once the fonts were read again. `unreadable` are families installed only as .otf files:
 * when no new font was found they are the likely reason, so they are named.
 */
export function reloadMessage(before: number | undefined, after: number, unreadable: string[] = []): string {
  const n = (count: number) => count.toLocaleString('ko-KR')
  const otf =
    unreadable.length > 0
      ? ` 이 PC에는 .otf 형식으로만 설치된 글꼴(${familyList(unreadable)})이 있는데, 이 앱은 .ttf만 읽을 수 있습니다. 같은 글꼴의 .ttf 버전을 설치해 주세요.`
      : ''
  if (before === undefined) return `글꼴 ${n(after)}개를 읽었습니다.${otf}`
  if (after > before) return `글꼴 ${n(before)}개 → ${n(after)}개: 새로 ${after - before}개를 찾았습니다.`
  if (after < before) return `글꼴 ${n(before)}개 → ${n(after)}개로 줄었습니다.`
  return `글꼴 ${n(after)}개: 새로 설치된 글꼴을 찾지 못했습니다. 설치가 끝났는지 확인한 뒤 다시 눌러 보세요.${otf}`
}
