import { describe, expect, it } from 'vitest'
import { keptPermissions } from './lock'
import type { PageItem } from './types'

const page = (key: string, lockedPermissions?: number): PageItem => ({
  key,
  documentId: 'doc-' + key,
  sourceFileName: key + '.pdf',
  pageIndex: 0,
  rotation: 0,
  textRevision: 0,
  ...(lockedPermissions === undefined ? {} : { lockedPermissions }),
})

const PRINT = 1 << 2
const COPY = 1 << 4

describe('keptPermissions', () => {
  it('is undefined when no page came from a protected document', () => {
    expect(keptPermissions([page('a'), page('b')])).toBeUndefined()
  })

  it('keeps what the protected original allowed', () => {
    expect(keptPermissions([page('a', -4 & ~PRINT), page('b')])).toBe(-4 & ~PRINT)
  })

  it('allows only what every protected original allowed', () => {
    const kept = keptPermissions([page('a', -4 & ~PRINT), page('b', -4 & ~COPY)])!

    expect(kept & PRINT).toBe(0)
    expect(kept & COPY).toBe(0)
    expect(kept & (1 << 3)).not.toBe(0)
  })
})
