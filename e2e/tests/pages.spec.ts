import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('a duplicated page comes right after its original, with what was typed on it', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'pages.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Original line'], 2),
  })
  await expect(page.getByText('2페이지')).toBeVisible()

  // Type over the first page's line, then go back to the list.
  await page.locator('button[title="텍스트 편집"]').first().click()
  await page.locator('.te-box[data-index="0"]').click()
  await page.keyboard.press('Enter')
  await page.keyboard.press('Control+a')
  await page.keyboard.type('Edited line')
  await page.keyboard.press('Enter')
  await expect(page.locator('.te-status')).toContainText('반영됨')
  await page.locator('.rb-back').click()

  await page.locator('.page-card').first().locator('button[title^="페이지 복제"]').click()
  await expect(page.getByText('3페이지')).toBeVisible()
  const copy = page.locator('.page-card').nth(1)
  await expect(copy.locator('.page-source')).toHaveText('pages.pdf')
  const src = await copy.locator('img').getAttribute('src')
  const copyId = /documents\/([^/]+)\//.exec(src ?? '')![1]
  const runs = await (await page.request.get(`/api/v1/pdf/documents/${copyId}/pages/0/text-runs`)).json()
  expect(runs.runs[0].currentText).toBe('Edited line')

  // Ctrl+D copies the selected pages.
  await page.locator('.page-card').nth(2).locator('.page-select input').check()
  await page.keyboard.press('Control+d')
  await expect(page.getByText('4페이지')).toBeVisible()
})

test('fitting the page shows the whole page in the editor', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'tall.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['A line']),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  const zoom = page.locator('.te-zoom-value')
  const widthFit = await zoom.textContent()

  await page.locator('.te-zoom').getByRole('button', { name: '쪽 맞춤' }).click()

  await expect(zoom).not.toHaveText(widthFit ?? '')
  const main = (await page.locator('.te-main').boundingBox())!
  const sheet = (await page.locator('.te-canvas img').boundingBox())!
  expect(sheet.height).toBeLessThanOrEqual(main.height)

  await page.keyboard.press('Control+0')
  await expect(zoom).toHaveText(widthFit ?? '')
})
