import type { Table, TableCell } from './tableGrid'
import { rows } from './textSelect'

/**
 * 금액 검산: a quote's or a statement's arithmetic checked from its table - every item's 수량 × 단가 against its 금액,
 * the items' 금액 added up against the 합계 (소계, 공급가액) row, the 세액 (부가세) against a tenth of what it is
 * levied on, and the 총액 against 공급가액 + 세액. The columns are told by their headings. After kordoc's 예산
 * 산출근거 검산 (MIT).
 */

/** A cell whose figure does not agree with the others, with what it should be. */
export interface SumProblem {
  cell: TableCell
  /** What the figures make it. */
  expected: number
  /** What the table says. */
  found: number
  /** Said for the user: which row, and how the figure should come out. */
  message: string
}

export interface SumCheck {
  /** Whether a table with amounts was found to check at all. */
  checked: boolean
  /** Item rows whose 수량 × 단가 was checked. */
  items: number
  /** Totals (합계, 세액, 총액) checked. */
  totals: number
  problems: SumProblem[]
}

type Column = 'qty' | 'unit' | 'amount' | 'vat' | 'total'

/** Headings of the columns, as quotes and statements write them (spaces taken out). */
const HEADINGS: [Column, RegExp][] = [
  ['qty', /^(수량|수 ?량|qty|q'?ty|quantity|개수|ea)$/i],
  ['unit', /^(단가|단 ?가|unitprice|price|unit)$/i],
  ['vat', /^(세액|부가세|부가가치세|vat|tax)$/i],
  ['total', /^(합계금액|총액|총금액|합계)$/i],
  ['amount', /^(금액|공급가액|공급가|amount|금액\(원\)|금액\(vat별도\))$/i],
]

/** The first text of a total row: 합계, 소계, 총계, 공급가액 합계, 계, TOTAL. */
const TOTAL_ROW = /^(합계|소계|총계|총액|계|합\s*계|소\s*계|공급가액\s*합계|total|sub\s*total|sum)/i
const VAT_ROW = /^(부가세|부가가치세|세액|vat)/i
const GRAND_ROW = /^(총\s*합계|총\s*액|총\s*금액|합계\s*금액|grand\s*total|총계)/i

/** A figure as written: "1,250", "220,300원", "₩ 8,000", "△3,000" (minus), "-1.5". Null when it is not one. */
export function amountOf(text: string): number | null {
  const t = text.replace(/\s/g, '').replace(/^₩|^\\/, '').replace(/(원|원정|KRW)$/i, '')
  const m = /^([-−△▲]?)(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?$/.exec(t)
  if (!m) return null
  const value = Number(m[2].replace(/,/g, '') + (m[3] ?? ''))
  return m[1] ? -value : value
}

const won = (n: number) => `${Math.round(n).toLocaleString('ko-KR')}`

/**
 * What a table hands on to the next when the next goes on with it (a quote's items, then its 계 row drawn as a table
 * of its own below another): where its columns are across the page, and the running sums.
 */
export interface Carry {
  spans: Map<Column, [number, number]>
  itemSum: number
  items: number
  supply: number | null
  vat: number | null
  /** The last total found: the 총액, else the 합계. */
  total: { value: number; cell: TableCell } | null
}

/** The table checked: what of its arithmetic does not agree. `carry`: what the table above handed on. */
export function checkSums(table: Table, carry?: Carry | null): SumCheck & { carry: Carry | null } {
  const result: SumCheck & { carry: Carry | null } = { checked: false, items: 0, totals: 0, problems: [], carry: null }
  const rowCount = table.ys.length - 1
  const textOf = (cell: TableCell) => rows(cell.texts).map((row) => row.map((t) => t.text.trim()).join(' ')).join(' ').trim()
  const at = (r: number, c: number) => table.cells.find((x) => x.row <= r && r < x.row + x.rowSpan && x.col <= c && c < x.col + x.colSpan)
  // The heading row: the first with at least two known headings, one of them an amount.
  let heading = -1
  const columns = new Map<Column, number>()
  for (let r = 0; r < rowCount && heading < 0; r++) {
    const found = new Map<Column, number>()
    for (const cell of table.cells.filter((x) => x.row === r)) {
      const label = textOf(cell).replace(/\s/g, '')
      const match = HEADINGS.find(([, pattern]) => pattern.test(label))
      if (match && !found.has(match[0])) found.set(match[0], cell.col)
    }
    if (found.size >= 2 && (found.has('amount') || found.has('total'))) {
      heading = r
      for (const [k, v] of found) columns.set(k, v)
    }
  }
  if (heading < 0 && carry) {
    // No headings: the columns lying where the table above had them, if this one goes on with it.
    for (const [column, [x0, x1]] of carry.spans) {
      let best = -1
      let overlap = 0
      for (let c = 0; c < table.xs.length - 1; c++) {
        const o = Math.min(x1, table.xs[c + 1]) - Math.max(x0, table.xs[c])
        if (o > overlap) {
          overlap = o
          best = c
        }
      }
      if (best >= 0 && overlap > 0.5 * (x1 - x0)) columns.set(column, best)
    }
    if (!columns.has('amount') && !columns.has('total')) return result
  } else if (heading < 0) {
    return result
  }
  result.checked = true
  const amountCol = columns.get('amount') ?? columns.get('total')!
  const figure = (r: number, c: number | undefined) => {
    if (c === undefined) return null
    const cell = at(r, c)
    if (!cell || cell.row !== r) return null
    const value = amountOf(textOf(cell))
    return value === null ? null : { cell, value }
  }
  /** The row's label: its first cell's text, or the first text in it. */
  const label = (r: number) => {
    const first = table.cells.filter((x) => x.row === r).sort((a, b) => a.col - b.col).map(textOf).find((t) => t !== '')
    return first ?? ''
  }
  let itemSum = heading < 0 && carry ? carry.itemSum : 0
  let items = heading < 0 && carry ? carry.items : 0
  let supply: number | null = heading < 0 && carry ? carry.supply : null
  let vat: number | null = heading < 0 && carry ? carry.vat : null
  let total: Carry['total'] = heading < 0 && carry ? carry.total : null
  for (let r = heading + 1; r < rowCount; r++) {
    const name = label(r).replace(/\s/g, '')
    const amount = figure(r, amountCol)
    if (GRAND_ROW.test(name) && amount) {
      total = amount
      // 총액 = 공급가액 + 세액 when both were said; else the items and the tax.
      const base = supply ?? (items > 0 ? itemSum : null)
      if (base !== null && vat !== null) {
        result.totals++
        if (Math.abs(base + vat - amount.value) > 1) {
          result.problems.push({
            cell: amount.cell,
            expected: base + vat,
            found: amount.value,
            message: `${label(r)}: 공급가액 ${won(base)} + 세액 ${won(vat)} = ${won(base + vat)}원인데 ${won(amount.value)}원으로 적혀 있습니다`,
          })
        }
      }
      continue
    }
    if (VAT_ROW.test(name) && amount) {
      const base = supply ?? (items > 0 ? itemSum : null)
      vat = amount.value
      if (base !== null) {
        result.totals++
        // A tenth, as it may be rounded down to the won or to ten won.
        const tenth = base / 10
        if (Math.abs(tenth - amount.value) > 10) {
          result.problems.push({
            cell: amount.cell,
            expected: Math.floor(tenth),
            found: amount.value,
            message: `${label(r)}: ${won(base)}원의 10%는 ${won(Math.floor(tenth))}원인데 ${won(amount.value)}원으로 적혀 있습니다`,
          })
        }
      }
      continue
    }
    if (TOTAL_ROW.test(name) && amount) {
      if (items > 0) {
        result.totals++
        if (Math.abs(itemSum - amount.value) > 1) {
          result.problems.push({
            cell: amount.cell,
            expected: itemSum,
            found: amount.value,
            message: `${label(r)}: 품목 ${items}개 금액을 더하면 ${won(itemSum)}원인데 ${won(amount.value)}원으로 적혀 있습니다`,
          })
        }
      }
      supply = amount.value
      total = amount
      // A 세액 column beside the total: a tenth of it.
      const tax = figure(r, columns.get('vat'))
      if (tax) {
        vat = tax.value
        result.totals++
        if (Math.abs(amount.value / 10 - tax.value) > 10) {
          result.problems.push({
            cell: tax.cell,
            expected: Math.floor(amount.value / 10),
            found: tax.value,
            message: `${label(r)} 세액: ${won(amount.value)}원의 10%는 ${won(Math.floor(amount.value / 10))}원인데 ${won(tax.value)}원으로 적혀 있습니다`,
          })
        }
      }
      itemSum = 0
      items = 0
      continue
    }
    if (!amount) continue
    // An item row: its 금액, and 수량 × 단가 where both are there.
    itemSum += amount.value
    items++
    const qty = figure(r, columns.get('qty'))
    const unit = figure(r, columns.get('unit'))
    if (qty && unit) {
      result.items++
      const expected = qty.value * unit.value
      // Within a won, or rounded to ten won as some programs do.
      if (Math.abs(expected - amount.value) > 1 && Math.abs(Math.round(expected / 10) * 10 - amount.value) > 0.5) {
        result.problems.push({
          cell: amount.cell,
          expected,
          found: amount.value,
          message: `${label(r) || `${r + 1}행`}: 수량 ${qty.value.toLocaleString('ko-KR')} × 단가 ${won(unit.value)} = ${won(expected)}원인데 ${won(amount.value)}원으로 적혀 있습니다`,
        })
      }
    }
    // A 세액 column on the item row: a tenth of its amount.
    const tax = figure(r, columns.get('vat'))
    if (tax) {
      result.items++
      if (Math.abs(amount.value / 10 - tax.value) > 10) {
        result.problems.push({
          cell: tax.cell,
          expected: Math.floor(amount.value / 10),
          found: tax.value,
          message: `${label(r) || `${r + 1}행`} 세액: ${won(amount.value)}원의 10%는 ${won(Math.floor(amount.value / 10))}원인데 ${won(tax.value)}원으로 적혀 있습니다`,
        })
      }
    }
  }
  const spans = new Map<Column, [number, number]>()
  for (const [column, c] of columns) spans.set(column, [table.xs[c], table.xs[c + 1]])
  result.carry = { spans, itemSum, items, supply, vat, total }
  return result
}

/** The quote's amount said above its table: "금 액 | 5,300,000 원정 (부가세 별도)", "일금 오백삼십만원정 (₩5,300,000)". */
const STATED = /^(견적\s*)?(금\s*액|합\s*계\s*금\s*액|총\s*액|견적\s*가|견적\s*금액|공급\s*가액)$/

function statedAmount(table: Table): { value: number; cell: TableCell } | null {
  const textOf = (cell: TableCell) => cell.texts.map((t) => t.text.trim()).join(' ').trim()
  for (const cell of table.cells) {
    if (!STATED.test(textOf(cell))) continue
    const next = table.cells.find((x) => x.row === cell.row && x.col === cell.col + cell.colSpan)
    if (!next) continue
    const text = textOf(next)
    // A figure written out: the one after ₩ (or \\), else the first one with thousands parted.
    const m = /[₩\\]\s*([\d,]+)/.exec(text) ?? /(\d{1,3}(?:,\d{3})+)/.exec(text)
    const value = m ? amountOf(m[1]) : null
    if (value !== null) return { value, cell: next }
  }
  return null
}

/** All the page's tables checked together; what the user is told. */
export function checkTables(tables: Table[]): { checks: SumCheck[]; problems: SumProblem[]; summary: string } {
  const results: (SumCheck & { carry: Carry | null })[] = []
  let carry: Carry | null = null
  for (const table of tables) {
    const result = checkSums(table, carry)
    if (result.checked) {
      results.push(result)
      carry = result.carry
    }
  }
  const checks: SumCheck[] = results
  const problems = checks.flatMap((c) => c.problems)
  // The amount the quote states above its table, against the table's own total.
  const final = carry?.total ?? null
  const stated = tables.map(statedAmount).find((x) => x !== null) ?? null
  if (stated && final && stated.cell !== final.cell) {
    results[results.length - 1].totals++
    if (Math.abs(stated.value - final.value) > 1) {
      problems.push({
        cell: stated.cell,
        expected: final.value,
        found: stated.value,
        message: `위에 적힌 금액 ${won(stated.value)}원이 표의 합계 ${won(final.value)}원과 다릅니다`,
      })
    }
  }
  if (checks.length === 0) {
    return { checks, problems, summary: '검산할 표를 찾지 못했습니다 (수량·단가·금액 같은 제목이 있는 표만 검산합니다).' }
  }
  const items = checks.reduce((n, c) => n + c.items, 0)
  const totals = checks.reduce((n, c) => n + c.totals, 0)
  const what = [items > 0 ? `품목 ${items}개` : '', totals > 0 ? `합계 ${totals}곳` : ''].filter(Boolean).join(', ')
  if (problems.length === 0) return { checks, problems, summary: `금액 검산: 이상 없습니다 (${what || '금액'} 확인).` }
  return {
    checks,
    problems,
    summary: `금액 검산: ${problems.length}곳이 맞지 않습니다 (${what} 확인). 빨간 칸을 확인하세요. ${problems.map((p) => p.message).join(' · ')}`,
  }
}
