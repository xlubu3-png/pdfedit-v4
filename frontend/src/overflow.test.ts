import { describe, expect, it } from 'vitest'
import { overflows, rightLimit } from './overflow'
import type { PageText, TextRun } from './types'

function run(index: number, x: number, y: number, width: number, text: string): TextRun {
  return {
    index,
    text,
    currentText: text,
    edited: false,
    x,
    y,
    width,
    height: 12,
    fontSize: 12,
    boxBottom: y - 3,
    boxTop: y + 10,
    fontFamily: '맑은 고딕',
    bold: false,
    sourceFont: 'X',
    color: '#000000',
    glyphReuse: true,
    ownChars: text,
    sourceFontMissing: false,
    editFontFamily: null,
    editFontSize: null,
    editBold: null,
    editColor: null,
    dx: 0,
    dy: 0,
  }
}

function page(runs: TextRun[]): PageText {
  return { pageWidth: 600, pageHeight: 800, originX: 0, originY: 0, rotated: false, runs, added: [] }
}

describe('rightLimit', () => {
  it('stops at the next line on the same baseline', () => {
    const left = run(0, 50, 700, 100, 'left')
    const right = run(1, 300, 701, 80, 'right')
    expect(rightLimit(left, page([left, right]))).toBe(299)
  })

  it('ignores lines on other baselines and falls back to the page edge', () => {
    const a = run(0, 50, 700, 100, 'a')
    const below = run(1, 300, 650, 80, 'b')
    expect(rightLimit(a, page([a, below]))).toBe(600)
  })
})

describe('overflows', () => {
  const left = run(0, 50, 700, 100, '가나다라마바')
  const right = run(1, 200, 700, 80, '오른쪽')
  const data = page([left, right])

  it('is true when the longer text would reach the line on its right', () => {
    expect(overflows(left, data, '가나다라마바사아자차카타파하', '맑은 고딕', 12, false, 0)).toBe(true)
  })

  it('is false while the text still fits in front of the next line', () => {
    expect(overflows(left, data, '가나다라마바사', '맑은 고딕', 12, false, 0)).toBe(false)
  })

  it('is false for text that is not longer than before', () => {
    expect(overflows(left, data, '가나', '맑은 고딕', 12, false, 0)).toBe(false)
    expect(overflows(left, data, left.text, '맑은 고딕', 12, false, 0)).toBe(false)
  })
})
