import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('the zoom chosen in the editor is kept on the next page and when the editor is opened again', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'two.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Page'], 2) })
  await page.locator('button[title="텍스트 편집"]').first().click()
  const editor = page.getByRole('dialog', { name: '텍스트 편집' })
  const width = async () => Math.round((await page.locator('.te-canvas img').first().boundingBox())!.width)
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  const fitted = await width()

  const zoomIn = editor.getByLabel('확대', { exact: true })
  await zoomIn.click()
  await zoomIn.click()
  await expect.poll(width).not.toBe(fitted)
  const zoomed = await width()

  await editor.getByRole('button', { name: '다음 쪽' }).first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await expect.poll(width).toBe(zoomed)

  await editor.getByRole('button', { name: '페이지 목록' }).click()
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await expect.poll(width).toBe(zoomed)
})
