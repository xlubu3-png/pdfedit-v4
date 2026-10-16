import { describe, expect, it } from 'vitest'
import { cleanSpans, clearField, formatRange, lookAt, remapSpans, toggleFor } from './spans'
import type { Span } from './types'

const span = (start: number, end: number, look: Partial<Span>): Span => ({
  start,
  end,
  bold: null,
  color: null,
  size: null,
  underline: null,
  ...look,
})
const bold = (start: number, end: number): Span => span(start, end, { bold: true })
const red = (start: number, end: number): Span => span(start, end, { color: '#ff0000' })
const line = { bold: false, color: '#000000', size: 12 }

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

  it('keeps size and underline of the span it moves', () => {
    const big = span(6, 11, { size: 20, underline: true })
    expect(remapSpans([big], 'Hello World', 'Oh Hello World')).toEqual([span(9, 14, { size: 20, underline: true })])
  })
})

describe('formatRange', () => {
  it('makes only the picked letters bold', () => {
    expect(formatRange([], 6, 11, 11, { bold: true }, line)).toEqual([bold(6, 11)])
  })

  it('adds a colour on top of a weight already there', () => {
    const spans = formatRange([bold(6, 11)], 8, 11, 11, { color: '#ff0000' }, line)
    expect(spans).toEqual([bold(6, 8), span(8, 11, { bold: true, color: '#ff0000' })])
  })

  it('takes the span away when the picked letters are given the look of the line', () => {
    expect(formatRange([red(0, 5)], 0, 5, 11, { color: '#000000' }, line)).toEqual([])
    expect(formatRange([span(0, 5, { size: 20 })], 0, 5, 11, { size: 12 }, line)).toEqual([])
  })

  it('joins neighbours that end up alike', () => {
    expect(formatRange([bold(0, 3)], 3, 6, 11, { bold: true }, line)).toEqual([bold(0, 6)])
  })

  it('sets a size and an underline on the picked letters', () => {
    const spans = formatRange([], 2, 5, 11, { size: 18, underline: true }, line)
    expect(spans).toEqual([span(2, 5, { size: 18, underline: true })])
  })
})

describe('toggleFor and lookAt', () => {
  it('turns bold on unless every picked letter is bold already', () => {
    expect(toggleFor([], 0, 5, 'bold', line)).toBe(true)
    expect(toggleFor([bold(0, 3)], 0, 5, 'bold', line)).toBe(true)
    expect(toggleFor([bold(0, 5)], 0, 5, 'bold', line)).toBe(false)
  })

  it('counts a bold line as bold throughout', () => {
    expect(toggleFor([], 0, 5, 'bold', { ...line, bold: true })).toBe(false)
  })

  it('does the same for underline', () => {
    expect(toggleFor([], 0, 5, 'underline', line)).toBe(true)
    expect(toggleFor([span(0, 5, { underline: true })], 0, 5, 'underline', line)).toBe(false)
  })

  it('tells what one letter looks like', () => {
    const spans = [span(2, 4, { size: 20, color: '#00ff00' })]
    expect(lookAt(spans, 3, line)).toEqual({ bold: false, color: '#00ff00', size: 20, underline: false })
    expect(lookAt(spans, 0, line)).toEqual({ bold: false, color: '#000000', size: 12, underline: false })
  })
})

describe('clearField and cleanSpans', () => {
  it('a change to the whole line clears just that one thing from the spans', () => {
    const both = span(0, 4, { bold: true, color: '#ff0000' })
    expect(clearField([both], 'bold')).toEqual([red(0, 4)])
    expect(clearField([bold(0, 4)], 'bold')).toEqual([])
  })

  it('clips spans to the text and drops what changes nothing', () => {
    expect(cleanSpans([bold(8, 20), span(0, 3, {})], 10)).toEqual([bold(8, 10)])
  })
})
