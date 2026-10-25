import { describe, expect, it } from 'vitest'
import { mergeAreas, newAreaId, parseRedactionReport, redactionMessage } from './redaction'

describe('redaction report', () => {
  it('reads the header the export sends', () => {
    expect(parseRedactionReport('areas=3;removed=41;pictures=2,5')).toEqual({ areas: 3, removed: 41, pictures: [2, 5] })
    expect(parseRedactionReport('areas=1;removed=13;pictures=')).toEqual({ areas: 1, removed: 13, pictures: [] })
    expect(parseRedactionReport(null)).toBeNull()
    expect(parseRedactionReport('nonsense')).toBeNull()
  })

  it('says nothing when nothing was redacted, and names the pages that became pictures', () => {
    expect(redactionMessage({ areas: 0, removed: 0, pictures: [] })).toBeNull()
    expect(redactionMessage(null)).toBeNull()

    const plain = redactionMessage({ areas: 2, removed: 20, pictures: [] })!
    expect(plain).toContain('2곳')
    expect(plain).toContain('20개')
    expect(plain).not.toContain('그림으로')

    expect(redactionMessage({ areas: 2, removed: 0, pictures: [1, 3] })).toContain('1, 3쪽은')
  })
})

describe('mergeAreas', () => {
  it('adds new areas and leaves out one that is already there', () => {
    const a = { id: 'a', x: 10, y: 20, width: 30, height: 10 }
    const again = { id: 'b', x: 10.2, y: 20.1, width: 30, height: 10 }
    const other = { id: 'c', x: 100, y: 20, width: 30, height: 10 }

    expect(mergeAreas([a], [again, other]).map((r) => r.id)).toEqual(['a', 'c'])
  })

  it('gives every new area its own id', () => {
    expect(newAreaId()).not.toBe(newAreaId())
  })
})
