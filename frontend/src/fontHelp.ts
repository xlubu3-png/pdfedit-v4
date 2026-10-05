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

/** What to tell the user once the fonts were read again. */
export function reloadMessage(before: number | undefined, after: number): string {
  const n = (count: number) => count.toLocaleString('ko-KR')
  if (before === undefined) return `글꼴 ${n(after)}개를 읽었습니다.`
  if (after > before) return `글꼴 ${n(before)}개 → ${n(after)}개: 새로 ${after - before}개를 찾았습니다.`
  if (after < before) return `글꼴 ${n(before)}개 → ${n(after)}개로 줄었습니다.`
  return `글꼴 ${n(after)}개: 새로 설치된 글꼴을 찾지 못했습니다. 설치가 끝났는지 확인한 뒤 다시 눌러 보세요.`
}
