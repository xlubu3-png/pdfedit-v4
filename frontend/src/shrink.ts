/** What "용량 줄이기" did on export (the server's X-Shrink-Report header). */
export interface ShrinkReport {
  /** Pictures stored again, smaller. */
  pictures: number
  /** Their stored size before and after, bytes. */
  before: number
  after: number
}

/** Reads "pictures=12;before=24000000;after=3100000"; null when there is no such header. */
export function parseShrinkReport(header: string | null): ShrinkReport | null {
  if (!header) return null
  const fields = new Map(
    header.split(';').map((part) => {
      const at = part.indexOf('=')
      return [part.slice(0, at).trim(), Number(part.slice(at + 1).trim())] as const
    }),
  )
  const pictures = fields.get('pictures')
  const before = fields.get('before')
  const after = fields.get('after')
  if (![pictures, before, after].every((n) => n !== undefined && Number.isFinite(n) && n >= 0)) return null
  return { pictures: pictures!, before: before!, after: after! }
}

/** A size for people: "850KB", "12.4MB". */
export function sizeText(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/** The sentence shown after an export asked to be small; `fileSize` is the size of the file received. */
export function shrinkMessage(report: ShrinkReport | null, fileSize: number, flatten: boolean): string {
  if (flatten) return `용량 줄이기: 페이지를 그림으로 바꾸면서 해상도를 낮춰 저장했습니다 (파일 ${sizeText(fileSize)}).`
  if (!report || report.pictures === 0) {
    return `용량 줄이기: 더 줄일 그림이 없었습니다. 그림이 이미 작거나, 줄이면 흐려질 그림입니다 (파일 ${sizeText(fileSize)}).`
  }
  return (
    `용량 줄이기: 그림 ${report.pictures}개를 다시 저장해 ${sizeText(report.before - report.after)}를 줄였습니다 ` +
    `(파일 ${sizeText(fileSize)}).`
  )
}
