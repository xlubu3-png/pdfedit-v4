import { expect, test } from '@playwright/test'

test('the retention is shown on the first screen and can be changed there', async ({ page }) => {
  await page.goto('/')
  const link = page.getByRole('button', { name: '작업 보관 30일' })
  await expect(link).toBeVisible()

  await link.click()
  const dialog = page.getByRole('dialog', { name: '작업 보관 기간' })
  await dialog.getByLabel(/보관할 기간/).selectOption('90')
  await dialog.getByRole('button', { name: '저장' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: '작업 보관 90일' })).toBeVisible()
  expect(await (await page.request.get('/api/v1/pdf/settings')).json()).toEqual({ retentionDays: 90 })

  // Back to the default, so the other tests see the app as it starts.
  await page.request.put('/api/v1/pdf/settings', { data: { retentionDays: 30 } })
})
