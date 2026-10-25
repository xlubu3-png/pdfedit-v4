import type { PersonalInfoKind, Redaction } from './types'

/** What the export did with the redaction areas (the server's X-Redaction-Report header). */
export interface RedactionReport {
  areas: number
  /** Letters cut out of the pages. */
  removed: number
  /** Pages (1-based, in the exported file) that were made pictures to be sure. */
  pictures: number[]
}

/** Reads "areas=3;removed=41;pictures=2,5"; null when there is no such header. */
export function parseRedactionReport(header: string | null): RedactionReport | null {
  if (!header) return null
  const fields = new Map(
    header.split(';').map((part) => {
      const at = part.indexOf('=')
      return [part.slice(0, at).trim(), part.slice(at + 1).trim()] as const
    }),
  )
  const areas = Number(fields.get('areas'))
  const removed = Number(fields.get('removed'))
  if (!Number.isFinite(areas) || !Number.isFinite(removed)) return null
  const pictures = (fields.get('pictures') ?? '')
    .split(',')
    .map((p) => Number(p))
    .filter((p) => Number.isInteger(p) && p > 0)
  return { areas, removed, pictures }
}

/** The sentence shown after an export that redacted something; null when nothing was. */
export function redactionMessage(report: RedactionReport | null): string | null {
  if (!report || report.areas === 0) return null
  let message =
    `가림 영역 ${report.areas}곳을 적용했습니다. 그 아래 글자 ${report.removed}개를 PDF에서 지우고, ` +
    '다시 읽어서 남은 글자가 없는 것을 확인했습니다.'
  if (report.pictures.length > 0) {
    message +=
      ` ${report.pictures.join(', ')}쪽은 가림 영역 아래에 그림 등이 있어 쪽 전체를 그림으로 바꿨습니다` +
      ' (그 쪽의 글자는 검색·복사되지 않습니다).'
  }
  return message
}

/** The kinds the search offers, in the order shown; the ones on at first are the clearly personal ones. */
export const PERSONAL_KINDS: { kind: PersonalInfoKind; label: string; on: boolean }[] = [
  { kind: 'RRN', label: '주민·외국인등록번호', on: true },
  { kind: 'MOBILE', label: '휴대전화번호', on: true },
  { kind: 'PHONE', label: '전화번호', on: true },
  { kind: 'EMAIL', label: '이메일', on: true },
  { kind: 'CARD', label: '카드번호', on: true },
  { kind: 'ACCOUNT', label: '계좌번호(추정)', on: true },
  { kind: 'DRIVER', label: '운전면허번호', on: true },
  { kind: 'PASSPORT', label: '여권번호', on: true },
  { kind: 'BUSINESS', label: '사업자등록번호', on: false },
]

let lastId = 0

export function newAreaId(): string {
  lastId += 1
  return `r${Date.now().toString(36)}${lastId}`
}

/** The existing areas plus the added ones, leaving out an added one that is already there. */
export function mergeAreas(existing: Redaction[], added: Redaction[]): Redaction[] {
  const same = (a: Redaction, b: Redaction) =>
    Math.abs(a.x - b.x) < 0.5 &&
    Math.abs(a.y - b.y) < 0.5 &&
    Math.abs(a.width - b.width) < 0.5 &&
    Math.abs(a.height - b.height) < 0.5
  const merged = [...existing]
  for (const area of added) {
    if (!merged.some((m) => same(m, area))) merged.push(area)
  }
  return merged
}
