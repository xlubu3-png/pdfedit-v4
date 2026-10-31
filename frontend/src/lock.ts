import type { PageItem } from './types'

/**
 * What an export of these pages allows when it is locked again: only what every protected original allowed
 * (PDF permission bits, ANDed). Undefined when none of the pages came from a protected document.
 */
export function keptPermissions(pages: PageItem[]): number | undefined {
  let kept: number | undefined
  for (const page of pages) {
    if (page.lockedPermissions === undefined) continue
    kept = kept === undefined ? page.lockedPermissions : kept & page.lockedPermissions
  }
  return kept
}
