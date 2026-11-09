import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { tablePdf } from './fixtures'

test('an item list running over two pages is saved to Excel joined on one sheet, its heading once', async ({ page }) => {
  await page.goto('/')
  const widths = [90, 50, 80]
  await page.locator('input[type=file]').setInputFiles([
    { name: 'p1.pdf', mimeType: 'application/pdf', buffer: tablePdf([['Item', 'Qty', 'Amount'], ['Fan', '2', '220,300'], ['Filter', '3', '19,770']], widths) },
    { name: 'p2.pdf', mimeType: 'application/pdf', buffer: tablePdf([['Item', 'Qty', 'Amount'], ['Switch', '1', '8,000']], widths) },
  ])
  await expect(page.locator('.page-card')).toHaveCount(2)

  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '엑셀로 내보내기' }).click()])
  await expect(page.locator('.info-banner[role=status]').last()).toContainText("여러 쪽에 걸친 표 1개는 맨 앞 '이어진 표' 시트에 한데 모음")
  const book = readFileSync((await download.path())!).toString('utf8')
  expect(book).toContain('이어진 표 1~2쪽')
  // The joined sheet: the heading once, then all three items.
  const sheet1 = /xl\/worksheets\/sheet1\.xml[\s\S]*?<\/worksheet>/.exec(book)![0]
  expect(sheet1.match(/>Item</g)).toHaveLength(1)
  for (const item of ['Fan', 'Filter', 'Switch']) expect(sheet1).toContain(`>${item}<`)
})
