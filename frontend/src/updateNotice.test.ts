// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { readSkippedVersion, shortNotes, shouldAnnounce, skipVersion } from './updateNotice'
import type { UpdateInfo } from './types'

const info = (patch: Partial<UpdateInfo> = {}): UpdateInfo => ({
  enabled: true,
  current: '0.051',
  latest: '0.052',
  newer: true,
  releaseUrl: 'https://example.test/r',
  notes: null,
  error: null,
  ...patch,
})

beforeEach(() => localStorage.clear())

describe('shouldAnnounce', () => {
  it('announces a newer version', () => {
    expect(shouldAnnounce(info(), null)).toBe(true)
  })

  it('stays quiet when updating is off, nothing is newer, or the check has not answered', () => {
    expect(shouldAnnounce(info({ enabled: false }), null)).toBe(false)
    expect(shouldAnnounce(info({ newer: false }), null)).toBe(false)
    expect(shouldAnnounce(info({ latest: null }), null)).toBe(false)
    expect(shouldAnnounce(null, null)).toBe(false)
  })

  it('stays quiet for the version the user skipped, but not for a later one', () => {
    skipVersion('0.052')

    expect(shouldAnnounce(info(), readSkippedVersion())).toBe(false)
    expect(shouldAnnounce(info({ latest: '0.053' }), readSkippedVersion())).toBe(true)
  })
})

describe('shortNotes', () => {
  it('flattens markdown and cuts long text', () => {
    expect(shortNotes('## 새 기능\n- 글꼴 안내\n- **업데이트**')).toBe('새 기능 글꼴 안내 업데이트')
    expect(shortNotes('a'.repeat(300), 10)).toBe('aaaaaaaaaa…')
    expect(shortNotes(null)).toBe('')
  })
})
