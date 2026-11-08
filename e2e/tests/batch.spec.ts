import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import { scannedPdf, simplePdf } from './fixtures'

test('several files are each recognized and saved on their own, in one ZIP', async ({ page }) => {
  await page.goto('/')
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1654
    canvas.height = 2339
    const g = canvas.getContext('2d')!
    g.fillStyle = '#fff'
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.fillStyle = '#000'
    g.font = '44px "Malgun Gothic", sans-serif'
    g.fillText('연락처 010-2345-6789', 200, 400)
    return canvas.toDataURL('image/jpeg', 0.9)
  })
  await page.locator('input[type=file]').setInputFiles([
    { name: '스캔.pdf', mimeType: 'application/pdf', buffer: scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 1654, 2339) },
    { name: '견적서.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Quotation 2026', 'Total 240,070'], 2) },
  ])
  await expect(page.locator('.page-card')).toHaveCount(3)
  await page.getByRole('button', { name: '나중에' }).click()

  await page.getByRole('button', { name: '파일마다 처리' }).click()
  const dialog = page.getByRole('dialog', { name: '파일마다 처리' })
  await expect(dialog.getByRole('checkbox', { name: /스캔\.pdf/ })).toBeChecked()
  await expect(dialog.getByRole('checkbox', { name: /견적서\.pdf/ })).toBeChecked()
  await dialog.getByRole('checkbox', { name: /엑셀 파일/ }).check()

  const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: '실행' }).click()])
  expect(download.suggestedFilename()).toMatch(/^일괄처리_\d{8}_\d{4}\.zip$/)
  const zip = readFileSync((await download.path())!)
  const names = zip.toString('utf8')
  for (const name of ['스캔.pdf', '스캔.xlsx', '견적서.pdf', '견적서.xlsx']) expect(names).toContain(name)
  // The scan's PDF now carries the recognized text.
  expect(names).toContain('%PDF')
  await expect(page.locator('.info-banner[role=status]').last()).toContainText('파일 2개를 처리해 4개로 저장했습니다')
  await expect(page.locator('.page-card .page-ocr')).toHaveText('OCR')
})

test('one file and one kind of result is saved as it is, not in a ZIP', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: '견적서.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Quotation 2026']),
  })
  await page.getByRole('button', { name: '파일마다 처리' }).click()
  const dialog = page.getByRole('dialog', { name: '파일마다 처리' })
  await dialog.getByRole('checkbox', { name: /글자 인식/ }).uncheck()
  const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('button', { name: '실행' }).click()])
  expect(download.suggestedFilename()).toBe('견적서.pdf')
  expect(readFileSync((await download.path())!).subarray(0, 5).toString()).toBe('%PDF-')
})
