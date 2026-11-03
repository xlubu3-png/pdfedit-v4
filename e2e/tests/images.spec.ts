import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { simplePdf } from './fixtures'

async function saved(page: Page, start: () => Promise<void>): Promise<{ name: string; bytes: Buffer }> {
  await start()
  const dialog = page.getByRole('dialog', { name: '사진으로 내보내기' })
  await expect(dialog).toBeVisible()
  const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: '사진으로 저장' }).click()])
  await expect(dialog).toBeHidden()
  return { name: download.suggestedFilename(), bytes: readFileSync((await download.path())!) }
}

test('"사진으로 내보내기" saves all pages as a ZIP of pictures, and one page as one picture', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: '계약서.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Contract line'], 3),
  })
  await expect(page.getByText('3페이지')).toBeVisible()

  const all = await saved(page, async () => {
    await page.getByRole('button', { name: '사진으로 내보내기' }).click()
    await page.getByRole('radio', { name: /PNG/ }).check()
  })
  expect(all.name).toBe('계약서_수정본.zip')
  expect(all.bytes.subarray(0, 2).toString('latin1')).toBe('PK')
  for (const n of ['01', '02', '03']) expect(all.bytes.includes(Buffer.from(`계약서_수정본_${n}.png`))).toBe(true)
  await expect(page.locator('.info-banner')).toContainText('사진 3장을 ZIP 파일로 저장했습니다')

  // From the page's right-click menu: just that page, in the kind picked last time.
  const one = await saved(page, async () => {
    await page.locator('.page-card').nth(1).click({ button: 'right' })
    await page.getByRole('menuitem', { name: /사진으로 저장/ }).click()
    await expect(page.getByRole('radio', { name: /PNG/ })).toBeChecked()
  })
  expect(one.name).toBe('계약서_수정본_2쪽.png')
  expect(one.bytes.subarray(1, 4).toString('latin1')).toBe('PNG')
})
