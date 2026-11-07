import type { UpdateInfo } from './types'

const KEY = 'wintech-pdf-update-skipped'

/** The version the user chose to skip ("나중에"), so the notice does not come back for it on every start. */
export function readSkippedVersion(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function skipVersion(version: string): void {
  try {
    localStorage.setItem(KEY, version)
  } catch {
    // private mode or storage full: the notice simply comes back next time
  }
}

/** Whether to tell the user about this update: there is one, and it is not the version they skipped. */
export function shouldAnnounce(info: UpdateInfo | null, skipped: string | null): boolean {
  return info !== null && info.enabled && info.newer && info.latest !== null && info.latest !== skipped
}

/** The first lines of the release notes, as one short text for the banner. */
export function shortNotes(notes: string | null, maxChars = 140): string {
  const text = (notes ?? '')
    .replace(/[#*`>_-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > maxChars ? `${text.slice(0, maxChars)}…` : text
}

/** What to tell the user after an update was started: that it worked, or that it did not and how to go on. */
export function outcomeNote(info: UpdateInfo | null): string | null {
  const outcome = info?.outcome
  if (!outcome) return null
  return outcome.installed
    ? `v${info.current}(으)로 업데이트했습니다.`
    : `v${outcome.to} 업데이트가 끝나지 않아 v${info.current} 그대로입니다. 설치 창이 취소되었거나 설치에 실패했을 수 있습니다. 업데이트를 다시 해 주세요.`
}
