import type { OcrResult, PageItem } from './types'

/** What a text recognition run did. */
export interface OcrSummary {
  /** Pages recognized (fewer than asked when stopped). */
  pages: number
  /** Lines of text found, over all pages. */
  lines: number
  /** Lines found per page key. */
  linesByKey: Record<string, number>
  /** Pages that only had real text, which is not recognized a second time. */
  alreadyText: number
  stopped: boolean
}

/**
 * Recognizes the pages one after another (each takes a second or two), telling `onProgress` how many are done,
 * and stops before the next page once `stopped()` says so.
 */
export async function recognizeAll(
  pages: PageItem[],
  recognize: (documentId: string, pageIndex: number) => Promise<OcrResult>,
  onProgress: (done: number, total: number) => void,
  stopped: () => boolean,
): Promise<OcrSummary> {
  const summary: OcrSummary = { pages: 0, lines: 0, linesByKey: {}, alreadyText: 0, stopped: false }
  onProgress(0, pages.length)
  for (const page of pages) {
    if (stopped()) {
      summary.stopped = true
      break
    }
    const result = await recognize(page.documentId, page.pageIndex)
    summary.pages++
    summary.lines += result.lines.length
    summary.linesByKey[page.key] = result.lines.length
    if (result.lines.length === 0 && result.alreadyText > 0) summary.alreadyText++
    onProgress(summary.pages, pages.length)
  }
  return summary
}

export function ocrMessage(summary: OcrSummary): string {
  const parts: string[] = []
  if (summary.lines > 0) {
    parts.push(
      `${summary.pages}쪽에서 글자 ${summary.lines}줄을 인식했습니다. 내보낸 PDF에서 글자를 검색·복사할 수 있습니다.`,
    )
  } else {
    parts.push('인식할 글자가 없었습니다.')
  }
  if (summary.alreadyText > 0) parts.push(`${summary.alreadyText}쪽은 이미 글자가 들어 있는 PDF입니다.`)
  if (summary.stopped) parts.push('나머지 쪽은 중지했습니다.')
  return parts.join(' ')
}

/** The next line from `from` on (going `step`) read with less confidence than `unsure`; null when there is none. */
export function nextUnsure(lines: { score: number }[], from: number, step: 1 | -1, unsure: number): number | null {
  for (let i = from + step; i >= 0 && i < lines.length; i += step) {
    if (lines[i].score < unsure) return i
  }
  return null
}

/**
 * What setting pages straight did, to go before the recognition's message; empty when no page was turned.
 * `upright` of them were scanned on their side or upside down and turned the right way up.
 */
export function straightenedNote(pages: number, upright = 0): string {
  if (pages === 0) return ''
  const what = upright > 0 ? `방향·기울기를 바로잡았습니다 (뒤집히거나 누운 쪽 ${upright}쪽)` : '기울기를 바로잡았습니다'
  return `${pages}쪽의 ${what}. Ctrl+Z로 되돌릴 수 있습니다. `
}
