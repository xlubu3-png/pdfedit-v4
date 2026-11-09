import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { tablePdf } from './fixtures'

async function openQuote(page: Page, rows: string[][]) {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'quote.pdf',
    mimeType: 'application/pdf',
    buffer: tablePdf(rows, [90, 50, 70, 80]),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: /금액 검산/ }).click()
}

test('금액 검산 points at an amount that is not 수량 × 단가, and at a total that does not add up', async ({ page }) => {
  await openQuote(page, [
    ['Item', 'Qty', 'Price', 'Amount'],
    ['Fan', '2', '110,150', '220,000'],
    ['Filter', '3', '6,590', '19,770'],
    ['Total', '', '', '240,070'],
  ])
  const note = page.locator('.te-sumcheck-note')
  await expect(note).toContainText('금액 검산: 2곳이 맞지 않습니다')
  await expect(note).toContainText('Fan: 수량 2 × 단가 110,150 = 220,300원인데 220,000원으로 적혀 있습니다')
  await expect(note).toContainText('Total: 품목 2개 금액을 더하면 239,770원인데 240,070원으로')
  await expect(page.locator('.te-sumcheck-cell')).toHaveCount(2)

  await note.getByRole('button', { name: '검산 결과 닫기' }).click()
  await expect(page.locator('.te-sumcheck-cell')).toHaveCount(0)
})

test('a quote that adds up is said to be right', async ({ page }) => {
  await openQuote(page, [
    ['Item', 'Qty', 'Price', 'Amount'],
    ['Fan', '2', '110,150', '220,300'],
    ['Total', '', '', '220,300'],
  ])
  await expect(page.locator('.te-sumcheck-note')).toContainText('금액 검산: 이상 없습니다 (품목 1개, 합계 1곳 확인)')
  await expect(page.locator('.te-sumcheck-cell')).toHaveCount(0)
})
