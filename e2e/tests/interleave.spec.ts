import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('앞·뒷면 합치기 takes the fronts and the backs (scanned last page first) in turn, and Ctrl+Z takes it back', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: '앞면.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Front'], 3) })
  await expect(page.locator('.page-card')).toHaveCount(3)
  await page.locator('input[type=file]').setInputFiles({ name: '뒷면.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Back'], 3) })
  await expect(page.locator('.page-card')).toHaveCount(6)

  /** Each card as "file:page" (the page of its own file, from 1). */
  const order = async () =>
    page.locator('.page-card').evaluateAll((cards) =>
      cards.map((c) => {
        const src = c.querySelector('img')?.getAttribute('src') ?? ''
        const file = (c.textContent ?? '').includes('앞면.pdf') ? '앞' : '뒤'
        return `${file}${Number(/pages\/(\d+)\//.exec(src)![1]) + 1}`
      }),
    )
  expect(await order()).toEqual(['앞1', '앞2', '앞3', '뒤1', '뒤2', '뒤3'])

  page.once('dialog', (d) => void d.accept()) // the backs were scanned last page first
  await page.getByRole('button', { name: '앞·뒷면 합치기' }).click()
  await expect(page.getByText(/앞면과 뒷면을 번갈아 합쳤습니다/)).toBeVisible()
  expect(await order()).toEqual(['앞1', '뒤3', '앞2', '뒤2', '앞3', '뒤1'])

  await page.keyboard.press('Control+z')
  await expect.poll(order).toEqual(['앞1', '앞2', '앞3', '뒤1', '뒤2', '뒤3'])
})
