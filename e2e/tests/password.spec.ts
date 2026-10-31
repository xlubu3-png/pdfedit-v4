import { readFileSync } from 'node:fs'
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

test('a document opened with its password can be exported locked again, or plain', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'locked.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Secret line'], 1, 'pass1234'),
  })
  const ask = page.getByRole('dialog', { name: 'PDF 암호 입력' })
  await ask.getByLabel('암호').fill('pass1234')
  await ask.getByRole('button', { name: '열기', exact: true }).click()
  await expect(page.getByText('1페이지')).toBeVisible()

  await page.getByRole('button', { name: 'PDF로 내보내기' }).click()
  const lock = page.getByRole('dialog', { name: '내보낼 PDF에 암호 걸기' })
  await expect(lock).toContainText('원래 문서에 암호가 걸려 있었습니다')
  await lock.getByLabel('암호', { exact: true }).fill('new-pass')
  await lock.getByLabel('암호 확인').fill('new-pass')
  const [locked] = await Promise.all([
    page.waitForEvent('download'),
    lock.getByRole('button', { name: '암호 걸어 내보내기' }).click(),
  ])
  await expect(page.locator('.info-banner')).toContainText('암호를 걸어 내보냈습니다')

  // The app itself is the reader: the file asks for the new password, and opens with it.
  const file = { name: 'again.pdf', mimeType: 'application/pdf', buffer: readFileSync(await locked.path()) }
  expect((await page.request.post('/api/v1/pdf/documents', { multipart: { file } })).status()).toBe(401)
  const reopened = await page.request.post('/api/v1/pdf/documents', { multipart: { file, password: 'new-pass' } })
  expect(reopened.status()).toBe(200)
  expect((await reopened.json()).unlocked).toBe(true)

  await page.getByRole('button', { name: 'PDF로 내보내기' }).click()
  const [plain] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '암호 없이 내보내기' }).click(),
  ])
  const open = { name: 'plain.pdf', mimeType: 'application/pdf', buffer: readFileSync(await plain.path()) }
  expect((await (await page.request.post('/api/v1/pdf/documents', { multipart: { file: open } })).json()).unlocked).toBe(false)
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
