import { expect, test } from '@playwright/test'

test('an Office document is sent to be printed to PDF, and without Office the user is told where it works', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: '견적서.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('PK not really a workbook'),
  })
  // These tests run on a PC without MS Office (or not Windows): the file is not skipped, Office is asked for.
  await expect(page.locator('.error-banner')).toContainText(/MS Office|MS Excel/)
  await expect(page.locator('.error-banner')).not.toContainText('건너뛴 파일')
})
