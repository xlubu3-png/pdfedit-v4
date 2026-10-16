import { describe, expect, it } from 'vitest'
import { boldToggleFor, cleanSpans, clearField, formatRange, remapSpans } from './spans'
import type { Span } from './types'

const bold = (start: number, end: number): Span => ({ start, end, bold: true, color: null })
const red = (start: number, end: number): Span => ({ start, end, bold: null, color: '#ff0000' })
const line = { bold: false, color: '#000000' }

describe('remapSpans', () => {
  it('shifts a span when text is typed before it', () => {
    expect(remapSpans([bold(6, 11)], 'Hello World', 'Oh Hello World')).toEqual([bold(9, 14)])
  })

  it('leaves a span alone when text is typed after it', () => {
    expect(remapSpans([red(0, 5)], 'Hello World', 'Hello World!!')).toEqual([red(0, 5)])
  })

  it('lets what is typed inside a span join it', () => {
    expect(remapSpans([bold(6, 11)], 'Hello World', 'Hello Wonderful World')).toEqual([bold(6, 21)])
  })

  it('lets a span whose letters were all replaced cover the replacement', () => {
    expect(remapSpans([bold(6, 11)], 'Hello World', 'Hello Earth')).toEqual([bold(6, 11)])
  })

  it('drops a span whose letters were deleted', () => {
    expect(remapSpans([bold(5, 11)], 'Hello World', 'Hello')).toEqual([])
  })
})

describe('formatRange', () => {
  it('makes only the picked letters bold', () => {
    expect(formatRange([], 6, 11, 11, { bold: true }, line)).toEqual([bold(6, 11)])
  })

  it('adds a colour on top of a weight already there', () => {
    const spans = formatRange([bold(6, 11)], 8, 11, 11, { color: '#ff0000' }, line)
    expect(spans).toEqual([bold(6, 8), { start: 8, end: 11, bold: true, color: '#ff0000' }])
  })

  it('takes the span away when the picked letters are given the look of the line', () => {
    expect(formatRange([red(0, 5)], 0, 5, 11, { color: '#000000' }, line)).toEqual([])
  })

  it('joins neighbours that end up alike', () => {
    expect(formatRange([bold(0, 3)], 3, 6, 11, { bold: true }, line)).toEqual([bold(0, 6)])
  })
})

describe('boldToggleFor', () => {
  it('turns bold on unless every picked letter is bold already', () => {
    expect(boldToggleFor([], 0, 5, line)).toBe(true)
    expect(boldToggleFor([bold(0, 3)], 0, 5, line)).toBe(true)
    expect(boldToggleFor([bold(0, 5)], 0, 5, line)).toBe(false)
  })

  it('counts a bold line as bold throughout', () => {
    expect(boldToggleFor([], 0, 5, { bold: true, color: '#000000' })).toBe(false)
  })
})

describe('clearField and cleanSpans', () => {
  it('a change to the whole line clears just that one thing from the spans', () => {
    const both: Span = { start: 0, end: 4, bold: true, color: '#ff0000' }
    expect(clearField([both], 'bold')).toEqual([red(0, 4)])
    expect(clearField([bold(0, 4)], 'bold')).toEqual([])
  })

  it('clips spans to the text and drops what changes nothing', () => {
    expect(cleanSpans([bold(8, 20), { start: 0, end: 3, bold: null, color: null }], 10)).toEqual([bold(8, 10)])
  })
})
