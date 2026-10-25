import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('two versions of a document are compared and the changed words are marked on both', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles([
    { name: 'old.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Price 100 won', 'Same line'], 2) },
    { name: 'new.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Price 120 won', 'Same line', 'New clause'], 2) },
  ])
  await expect(page.getByText('4페이지')).toBeVisible()

  await page.getByRole('button', { name: '문서 비교' }).click()
  const view = page.getByRole('dialog', { name: '문서 비교' })
  await view.getByLabel(/이전 판/).selectOption({ label: 'old.pdf' })
  await view.getByLabel(/새 판/).selectOption({ label: 'new.pdf' })
  await view.getByRole('button', { name: '비교하기' }).click()

  // Both pages of each version changed the same way: 100 -> 120 and a new clause.
  await expect(view.locator('.cmp-summary')).toHaveText('바뀐 쪽 2 · 삭제 2어절 · 추가 6어절')
  await expect(view.getByText('1 / 2쪽')).toBeVisible()
  await expect(view.getByTestId('cmp-removed')).toHaveCount(1)
  await expect(view.getByTestId('cmp-added')).toHaveCount(2)
  await expect(view.locator('.cmp-list')).toContainText('− 100')
  await expect(view.locator('.cmp-list')).toContainText('+ New clause')

  await view.getByLabel('모양 차이').check()
  await expect(view.locator('.cmp-mark.look').first()).toBeVisible()

  await view.getByTitle('다음 바뀐 쪽').click()
  await expect(view.getByText('2 / 2쪽')).toBeVisible()
  await view.getByRole('button', { name: '← 페이지 목록' }).click()
  await expect(view).toBeHidden()
})
