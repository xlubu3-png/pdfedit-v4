import { describe, expect, it } from 'vitest'
import { lineOfCover, refitted } from './refit'
import type { AddedText, OcrText } from './types'

const line = (text: string, x: number, y: number, width: number, height: number): OcrText => ({
  text,
  x,
  y,
  width,
  height,
  score: 0.99,
})

const box = (text: string, read: string): AddedText => ({
  id: 'b1',
  text,
  x: 100,
  y: 700,
  fontFamily: 'HCR Dotum',
  fontSize: 21.5,
  bold: false,
  color: '#a0a0a0',
  spans: text === read ? [] : [{ start: 0, end: 2, bold: true, color: null, size: null, underline: null }],
  cover: { x: 90, y: 690, width: 300, height: 30, color: '#ffffff', patch: 'old', read },
})

const look: Omit<AddedText, 'id'> = {
  text: '진료비 계산서',
  x: 101,
  y: 701,
  fontFamily: 'Batang',
  fontSize: 16,
  bold: false,
  color: '#4e4e50',
  spans: [],
  cover: { x: 88, y: 688, width: 304, height: 34, color: '#f8f8f8', patch: 'new', read: '진료비 계산서' },
  charSpacing: 0,
}

describe('lineOfCover', () => {
  it('finds the line the cover was made for, among lines above, below and beside it', () => {
    const lines = [
      line('위 줄', 100, 740, 200, 20),
      line('진료비계산서', 100, 695, 280, 20),
      line('옆 칸', 420, 695, 100, 20),
      line('아래 줄', 100, 650, 200, 20),
    ]
    expect(lineOfCover(look.cover!, lines)).toBe(1)
  })

  it('finds none when the page holds no line there any more', () => {
    expect(lineOfCover(look.cover!, [line('아래 줄', 100, 650, 200, 20)])).toBeNull()
  })
})

describe('refitted', () => {
  it('takes the new look and reading of a box left as it was read', () => {
    const next = refitted(box('진료비계산서', '진료비계산서'), look)
    expect(next).toMatchObject({ id: 'b1', text: '진료비 계산서', fontFamily: 'Batang', fontSize: 16, color: '#4e4e50' })
    expect(next.cover?.patch).toBe('new')
  })

  it('keeps the text the user wrote, and its formatting, in the new look', () => {
    const next = refitted(box('치료비계산서', '진료비계산서'), look)
    expect(next).toMatchObject({ id: 'b1', text: '치료비계산서', fontFamily: 'Batang', fontSize: 16 })
    expect(next.spans).toEqual([{ start: 0, end: 2, bold: true, color: null, size: null, underline: null }])
  })
})
