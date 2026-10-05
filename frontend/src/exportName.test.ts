import { describe, expect, it } from 'vitest'
import { exportFileName } from './exportName'

const page = (documentId: string, sourceFileName: string) => ({ documentId, sourceFileName })

describe('exportFileName', () => {
  it('names the download after the one file the pages come from', () => {
    expect(exportFileName([page('a', '계약서.pdf'), page('a', '계약서.pdf')])).toBe('계약서_수정본.pdf')
  })

  it('ignores blank pages that were added by hand', () => {
    expect(exportFileName([page('a', 'report.PDF'), page('b', '빈 페이지')])).toBe('report_수정본.pdf')
  })

  it('falls back to merged.pdf when several files are combined', () => {
    expect(exportFileName([page('a', 'a.pdf'), page('b', 'b.pdf')])).toBe('merged.pdf')
  })

  it('falls back to merged.pdf with nothing to name it after', () => {
    expect(exportFileName([])).toBe('merged.pdf')
    expect(exportFileName([page('b', '빈 페이지')])).toBe('merged.pdf')
  })

  it('replaces characters a file name cannot hold', () => {
    expect(exportFileName([page('a', 'a/b:c*d.pdf')])).toBe('a_b_c_d_수정본.pdf')
  })
})
