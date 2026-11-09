import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { tablePdf } from './fixtures'

test('표 비교 says which item\'s price changed and which items were added or taken out, and saves it to Excel', async ({ page }) => {
  await page.goto('/')
  const widths = [90, 50, 70, 80]
  await page.locator('input[type=file]').setInputFiles([
    {
      name: 'offer1.pdf',
      mimeType: 'application/pdf',
      buffer: tablePdf([['Item', 'Qty', 'Price', 'Amount'], ['Fan', '2', '110,150', '220,300'], ['Switch', '1', '8,000', '8,000']], widths),
    },
    {
      name: 'offer2.pdf',
      mimeType: 'application/pdf',
      buffer: tablePdf([['Item', 'Qty', 'Price', 'Amount'], ['Fan', '2', '120,000', '240,000'], ['Lamp', '1', '15,000', '15,000']], widths),
    },
  ])
  await expect(page.locator('.page-card')).toHaveCount(2)
  await page.getByRole('button', { name: '문서 비교' }).click()
  const view = page.getByRole('dialog', { name: '문서 비교' })
  await view.getByRole('button', { name: '표 비교하기' }).click()

  const result = view.getByRole('region', { name: '표 비교 결과' })
  await expect(result).toContainText('바뀜 2 · 추가 1 · 빠짐 1')
  await expect(result.locator('tr.cmp-changed').first()).toContainText('Price')
  await expect(result.locator('tr.cmp-changed').first()).toContainText('110,150')
  await expect(result.locator('tr.cmp-changed').first()).toContainText('120,000')
  await expect(result.locator('tr.cmp-added')).toContainText('Lamp')
  await expect(result.locator('tr.cmp-removed')).toContainText('Switch')

  const [download] = await Promise.all([page.waitForEvent('download'), result.getByRole('button', { name: '엑셀로 저장' }).click()])
  expect(download.suggestedFilename()).toBe('offer1_표비교.xlsx')
  const book = readFileSync((await download.path())!).toString('utf8')
  for (const text of ['바뀜', 'Lamp', '<v>120000</v>']) expect(book).toContain(text)
})
