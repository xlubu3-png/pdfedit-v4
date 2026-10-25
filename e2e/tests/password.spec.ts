import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('an encrypted PDF asks for its password, says when it is wrong, then opens', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'locked.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Secret line'], 2, 'pass1234'),
  })

  const dialog = page.getByRole('dialog', { name: 'PDF 암호 입력' })
  await expect(dialog).toContainText('locked.pdf')
  await dialog.getByLabel('암호').fill('wrong')
  await dialog.getByRole('button', { name: '열기', exact: true }).click()
  await expect(dialog).toContainText('암호가 맞지 않습니다')

  await dialog.getByLabel('암호').fill('pass1234')
  await dialog.getByRole('button', { name: '열기', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('.info-banner')).toContainText('암호를 풀어서 열었습니다')
  await expect(page.getByText('2페이지')).toBeVisible()

  // The plain copy is what is edited: its text reads like any other document's.
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toContainText('Secret line')
})

test('turning the password question down opens nothing and says which file was left out', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'locked.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Secret line'], 1, 'pass1234'),
  })

  const dialog = page.getByRole('dialog', { name: 'PDF 암호 입력' })
  await dialog.getByRole('button', { name: '열지 않기' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.locator('.error-banner')).toContainText('locked.pdf')
  await expect(page.locator('button[title="텍스트 편집"]')).toHaveCount(0)
})
