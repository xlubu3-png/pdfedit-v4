import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { simplePdf, solidPng } from './fixtures'

async function openEditor(page: Page, name: string): Promise<string> {
  await page.locator('input[type=file]').first().setInputFiles({ name, mimeType: 'application/pdf', buffer: simplePdf(['Quotation']) })
  await page.locator('button[title="텍스트 편집"]').last().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await expect.poll(() => page.locator('.te-canvas img').first().evaluate((i: HTMLImageElement) => i.complete && i.naturalHeight > 0)).toBe(true)
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  return /documents\/([^/]+)\//.exec(src ?? '')![1]
}

const pictures = async (page: Page, documentId: string) =>
  (await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/text-runs`)).json()).images ?? []

test('a seal is registered once, stamped where clicked with its white see-through, and stamped in the same place next time', async ({ page }) => {
  await page.goto('/')
  const first = await openEditor(page, 'quote1.pdf')
  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: '도장·서명 찍기…' }).click()
  const panel = page.getByRole('dialog', { name: '도장·서명 찍기' })
  page.once('dialog', (d) => void d.accept('회사 직인'))
  await panel.locator('input[type=file]').setInputFiles({ name: 'seal.png', mimeType: 'image/png', buffer: solidPng(200, 200, [220, 30, 30]) })
  await expect(panel.getByText('회사 직인')).toBeVisible()

  await panel.getByRole('button', { name: '찍기' }).click()
  const layer = (await page.locator('.te-layer').boundingBox())!
  await page.mouse.click(layer.x + layer.width * 0.75, layer.y + 300)
  await expect(page.locator('.te-status')).toContainText("'회사 직인'을(를) 찍었습니다")
  await expect(page.locator('[data-image]')).toHaveCount(1)
  await expect.poll(async () => (await pictures(page, first)).length).toBe(1)
  const stamped = (await pictures(page, first))[0]
  expect(stamped.keyOutWhite).toBe(true)
  expect(stamped.width).toBe(70)

  // Another document: the seal goes where it went last.
  await page.getByRole('dialog', { name: '텍스트 편집' }).getByRole('button', { name: '페이지 목록' }).click()
  const second = await openEditor(page, 'quote2.pdf')
  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: '도장·서명 찍기…' }).click()
  await page.getByRole('dialog', { name: '도장·서명 찍기' }).getByRole('button', { name: '지난 자리에' }).click()
  await expect.poll(async () => (await pictures(page, second)).length).toBe(1)
  const again = (await pictures(page, second))[0]
  expect(again.x).toBeCloseTo(stamped.x, 0)
  expect(again.y).toBeCloseTo(stamped.y, 0)
})
