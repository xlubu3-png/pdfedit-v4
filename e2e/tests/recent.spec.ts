import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('work put aside is kept with its edits, opened again from the start screen, and deleted when forgotten', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'quote.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['First offer'], 2),
  })
  await expect(page.getByText('2페이지')).toBeVisible()
  const src = await page.locator('.page-card img').first().getAttribute('src')
  const documentId = /documents\/([^/]+)\//.exec(src ?? '')![1]

  // An edit, to see it come back.
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.locator('.te-box[data-index="0"]').click()
  await page.keyboard.press('Enter')
  await page.keyboard.press('Control+a')
  await page.keyboard.type('Second offer')
  await page.keyboard.press('Enter')
  await expect(page.locator('.te-status')).toContainText('반영됨')
  await page.locator('.rb-back').click()

  await page.getByRole('button', { name: '보관하고 비우기' }).click()
  await expect(page.locator('.page-card')).toHaveCount(0)
  const recent = page.getByRole('region', { name: '최근 작업' })
  await expect(recent.locator('li')).toHaveCount(1)
  await expect(recent).toContainText('quote.pdf')
  await expect(recent).toContainText('2쪽')

  // It outlives a reload, and the empty list stays empty.
  await page.reload()
  await expect(recent.locator('li')).toHaveCount(1)
  await expect(page.locator('.page-card')).toHaveCount(0)

  await recent.locator('.recent-open').click()
  await expect(page.locator('.page-card')).toHaveCount(2)
  await expect(recent).toHaveCount(0)
  const runs = await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)).json()
  expect(runs.runs[0].currentText).toBe('Second offer')

  // Put aside again and forgotten: the document goes from the server too.
  await page.getByRole('button', { name: '보관하고 비우기' }).click()
  page.once('dialog', (dialog) => void dialog.accept())
  await recent.getByRole('button', { name: 'quote.pdf 지우기' }).click()
  await expect(recent).toHaveCount(0)
  await expect.poll(async () => (await page.request.get(`/api/v1/pdf/documents/${documentId}`)).status()).toBe(404)
})
