import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { scannedPdf } from './fixtures'

/** A colour scan as a phone or copier makes it: a 300 dpi A4 page of grainy colour, stored as a fine JPEG. */
async function bigScan(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 2480
    canvas.height = 3508
    const g = canvas.getContext('2d')!
    const pixels = g.createImageData(canvas.width, canvas.height)
    for (let i = 0; i < pixels.data.length; i += 4) {
      const grain = Math.random() * 40
      pixels.data[i] = 200 + grain
      pixels.data[i + 1] = 190 + grain
      pixels.data[i + 2] = 170 + grain
      pixels.data[i + 3] = 255
    }
    g.putImageData(pixels, 0, 0)
    g.fillStyle = '#000'
    g.font = '60px sans-serif'
    g.fillText('Scanned contract, page 1', 300, 600)
    return canvas.toDataURL('image/jpeg', 0.95)
  })
  return scannedPdf(Buffer.from(dataUrl.split(',')[1], 'base64'), 2480, 3508)
}

async function exported(page: Page): Promise<Buffer> {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'PDF로 내보내기' }).click(),
  ])
  return readFileSync((await download.path())!)
}

test('"용량 줄이기" stores a scan again smaller, and says by how much', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: await bigScan(page) })
  await page.getByRole('button', { name: '나중에' }).click()

  const usual = await exported(page)

  const toggle = page.getByRole('button', { name: '용량 줄이기' })
  await toggle.click()
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')
  const small = await exported(page)

  expect(small.length).toBeLessThan(usual.length / 2)
  await expect(page.locator('.info-banner')).toContainText('용량 줄이기: 그림 1개를 다시 저장해')
})
