import { describe, expect, it } from 'vitest'
import { readingOrder, selectableLines } from './textSelect'
import type { SelectableLine } from './textSelect'
import type { AddedText, OcrText, PageText, RunState, TextRun } from './types'

const line = (key: string, left: number, top: number, height = 20): SelectableLine => ({
  key,
  text: key,
  left,
  top,
  width: 100,
  height,
  size: 17,
})

describe('readingOrder', () => {
  it('reads top to bottom, and the cells of one row left to right', () => {
    const lines = [line('아래', 10, 100), line('오른쪽 칸', 300, 41), line('제목', 10, 0), line('왼쪽 칸', 10, 40)]
    expect(readingOrder(lines).map((l) => l.key)).toEqual(['제목', '왼쪽 칸', '오른쪽 칸', '아래'])
  })
})

describe('selectableLines', () => {
  const run = (index: number, text: string, y: number): TextRun =>
    ({ index, text, currentText: text, x: 72, y, width: 200, height: 12, fontSize: 12, boxBottom: y - 3, boxTop: y + 11 }) as TextRun
  const page = (runs: TextRun[]): PageText => ({
    pageWidth: 595,
    pageHeight: 842,
    originX: 0,
    originY: 0,
    rotated: false,
    runs,
    added: [],
    images: [],
  })
  const ocr = (text: string, y: number): OcrText => ({ text, x: 100, y, width: 300, height: 20, score: 0.99 })

  it('takes the page\'s lines as edited, what recognition read but no box rewrites, and the boxes', () => {
    const states = new Map<number, RunState>([[1, { text: '고친 줄', dx: 10, dy: 0 } as RunState]])
    const box = { id: 'b', text: '상자 첫 줄\n상자 둘째 줄', x: 50, y: 300, fontSize: 10, rotation: -1 } as AddedText

    const lines = selectableLines(page([run(0, '원래 줄', 700), run(1, '옛 줄', 680)]), states,
      [ocr('인식한 줄', 500), ocr('고쳐 쓴 줄', 450)], new Set([1]), [box], 2)

    expect(lines.map((l) => l.text)).toEqual(['원래 줄', '고친 줄', '인식한 줄', '상자 첫 줄', '상자 둘째 줄'])
    const [original, edited, read, first, second] = lines
    // Points to pixels at 2 per point, y counted down from the page's top.
    expect(original).toMatchObject({ left: 144, top: (842 - 711) * 2, length: 400 })
    expect(edited).toMatchObject({ left: 164, length: undefined })
    expect(read).toMatchObject({ left: 200, top: (842 - 520) * 2, length: 600, vertical: false })
    expect(first).toMatchObject({ left: 100, top: (842 - 310) * 2, turn: -1 })
    expect(second.top - first.top).toBeCloseTo(10 * 1.2 * 2)
  })
})
