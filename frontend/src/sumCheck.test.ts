import { describe, expect, it } from 'vitest'
import { amountOf, checkSums, checkTables } from './sumCheck'
import { gridTable } from './tableGrid'
import type { Table } from './tableGrid'

/** A table of the given rows, each cell holding its text. */
function table(rows: string[][]): Table {
  const cols = Math.max(...rows.map((r) => r.length))
  const t = gridTable({ xs: Array.from({ length: cols + 1 }, (_, i) => i * 100), ys: rows.map((_, i) => i * 20).concat(rows.length * 20) })
  for (const cell of t.cells) {
    const text = rows[cell.row][cell.col] ?? ''
    if (text) cell.texts.push({ text, left: cell.left + 5, top: cell.top + 4, height: 12, width: text.length * 5 })
  }
  return t
}

describe('amountOf', () => {
  it('reads figures as quotes write them', () => {
    expect(amountOf('220,300')).toBe(220300)
    expect(amountOf('220,300원')).toBe(220300)
    expect(amountOf('₩ 8,000')).toBe(8000)
    expect(amountOf('△3,000')).toBe(-3000)
    expect(amountOf('1.5')).toBe(1.5)
    expect(amountOf('2대')).toBeNull()
    expect(amountOf('AIR SHOWER')).toBeNull()
  })
})

describe('checkSums', () => {
  const quote = (amounts: string[], total: string, vat: string, grand: string) =>
    table([
      ['품명', '수량', '단가', '금액'],
      ['팬 모터', '2', '110,150', amounts[0]],
      ['필터', '3', '6,590', amounts[1]],
      ['합계', '', '', total],
      ['부가세', '', '', vat],
      ['총액', '', '', grand],
    ])

  it('finds nothing wrong in a quote that adds up', () => {
    const check = checkSums(quote(['220,300', '19,770'], '240,070', '24,007', '264,077'))
    expect(check.checked).toBe(true)
    expect(check.items).toBe(2)
    expect(check.totals).toBe(3)
    expect(check.problems).toEqual([])
  })

  it('points at the amount that is not 수량 × 단가, and the totals that do not add up', () => {
    const check = checkSums(quote(['220,000', '19,770'], '240,070', '20,000', '260,070'))
    const said = check.problems.map((p) => p.message)
    expect(said[0]).toBe('팬 모터: 수량 2 × 단가 110,150 = 220,300원인데 220,000원으로 적혀 있습니다')
    expect(said[1]).toContain('합계: 품목 2개 금액을 더하면 239,770원인데 240,070원으로 적혀 있습니다')
    expect(said[2]).toContain('부가세: 240,070원의 10%는 24,007원인데 20,000원으로')
    expect(check.problems[0].cell.row).toBe(1)
    expect(check.problems[0].cell.col).toBe(3)
  })

  it('takes a tax rounded down to ten won', () => {
    expect(checkSums(quote(['220,300', '19,770'], '240,070', '24,000', '264,070')).problems).toEqual([])
  })

  it('checks a 세액 column beside each item', () => {
    const t = table([
      ['품목', '공급가액', '세액'],
      ['A', '10,000', '1,000'],
      ['B', '5,000', '600'],
    ])
    expect(checkSums(t).problems.map((p) => p.message)).toEqual(['B 세액: 5,000원의 10%는 500원인데 600원으로 적혀 있습니다'])
  })

  it('goes on into a 계 row drawn as a table of its own below, and checks the amount stated above', () => {
    const stated = table([['금 액', '5,300,000 원정 (부가세 별도)']])
    const items = table([
      ['품명', '수량', '단가', '금액'],
      ['AIR SHOWER', '1', '4,700,000', '4,700,000'],
      ['설치비', '', '', '600,000'],
    ])
    const total = table([['계', '', '', '5,300,000']])
    expect(checkTables([stated, items, total]).summary).toBe('금액 검산: 이상 없습니다 (품목 1개, 합계 2곳 확인).')
    const wrong = table([['금 액', '5,000,000 원정']])
    expect(checkTables([wrong, items, total]).summary).toContain('위에 적힌 금액 5,000,000원이 표의 합계 5,300,000원과 다릅니다')
  })

  it('leaves a table without amount headings alone', () => {
    const t = table([
      ['이름', '연락처'],
      ['김', '010'],
    ])
    expect(checkSums(t).checked).toBe(false)
    expect(checkTables([t]).summary).toContain('검산할 표를 찾지 못했습니다')
  })
})
