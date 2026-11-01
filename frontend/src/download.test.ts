// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { downloadBlob } from './download'

describe('downloadBlob', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('clicks a link to the file and gives the address back only later', () => {
    vi.useFakeTimers()
    const create = vi.fn(() => 'blob:x')
    const revoke = vi.fn()
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke })
    const clicked: { href: string; download: string; inPage: boolean }[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.href, download: this.download, inPage: document.body.contains(this) })
    })

    downloadBlob(new Blob(['%PDF']), '계약서.pdf')

    expect(clicked).toEqual([{ href: 'blob:x', download: '계약서.pdf', inPage: true }])
    expect(document.querySelectorAll('a').length).toBe(0)
    expect(revoke).not.toHaveBeenCalled()
    vi.advanceTimersByTime(60_000)
    expect(revoke).toHaveBeenCalledWith('blob:x')
  })
})
