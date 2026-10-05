import { describe, expect, it } from 'vitest'
import { parseSession, serializeSession } from './session'
import type { PageItem } from './types'

const page = (key: string, rotation: PageItem['rotation'] = 0): PageItem => ({
  key,
  documentId: 'doc-1',
  sourceFileName: '계약서.pdf',
  pageIndex: 2,
  rotation,
  textRevision: 7,
})

describe('session', () => {
  it('gives back the pages that were stored, with a fresh thumbnail revision', () => {
    const restored = parseSession(serializeSession([page('a'), page('b', 90)]))

    expect(restored).toEqual([
      { ...page('a'), textRevision: 0 },
      { ...page('b', 90), textRevision: 0 },
    ])
  })

  it('treats missing, broken or foreign data as an empty session', () => {
    expect(parseSession(null)).toEqual([])
    expect(parseSession('')).toEqual([])
    expect(parseSession('{ not json')).toEqual([])
    expect(parseSession('{"a":1}')).toEqual([])
    expect(parseSession('"text"')).toEqual([])
  })

  it('drops malformed entries but keeps the good ones', () => {
    const text = JSON.stringify([
      { key: 'ok', documentId: 'd', sourceFileName: 'f.pdf', pageIndex: 0, rotation: 0 },
      { key: 'no-document', sourceFileName: 'f.pdf', pageIndex: 0, rotation: 0 },
      { key: 'bad-rotation', documentId: 'd', sourceFileName: 'f.pdf', pageIndex: 0, rotation: 45 },
      { key: 'bad-page', documentId: 'd', sourceFileName: 'f.pdf', pageIndex: -1, rotation: 0 },
      null,
      7,
    ])

    expect(parseSession(text).map((p) => p.key)).toEqual(['ok'])
  })
})
