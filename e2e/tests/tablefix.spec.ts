import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { simplePdf } from './fixtures'

/** Opens a page of two "label   value" lines in the editor: too few rows for a table to be found by itself. */
async function openForm(page: Page): Promise<string> {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({
    name: 'form.pdf',
    mimeType: 'application/pdf',
    buffer: simplePdf(['Name          Kim', 'Phone         010-1234']),
  })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()
  await expect.poll(() => page.locator('.te-canvas img').first().evaluate((i: HTMLImageElement) => i.complete && i.naturalHeight > 0)).toBe(true)
  const src = await page.locator('.te-canvas img').first().getAttribute('src')
  return /documents\/([^/]+)\//.exec(src ?? '')![1]
}

/** The screen place of a point on the page (points from its top-left). */
async function at(page: Page, x: number, y: number) {
  const img = (await page.locator('.te-canvas img').first().boundingBox())!
  const k = img.width / 595
  return { x: img.x + x * k, y: img.y + y * k }
}

async function excelOf(page: Page): Promise<string> {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } }).then(() =>
      page.getByRole('menuitem', { name: /엑셀 파일로 저장/ }).click(),
    ),
  ])
  return readFileSync((await download.path())!).toString('utf8')
}

const cell = (sheet: string, text: string) =>
  new RegExp(`<c r="([A-Z]+)(\\d+)"[^>]*t="inlineStr"><is><t[^>]*>${text}</t>`).exec(sheet)

test('a table drawn by hand over text is split into its lines and columns, kept, and used for Excel', async ({ page }) => {
  const documentId = await openForm(page)
  // Found by itself, the two lines are rows of text: label and value in one cell.
  const before = await excelOf(page)
  expect(cell(before, 'Kim')).toBeNull()

  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: '표 칸 바로잡기…' }).click()
  const bar = page.getByRole('toolbar', { name: '표 칸 바로잡기' })
  await expect(bar.getByRole('button', { name: '새 표 그리기' })).toHaveAttribute('aria-pressed', 'true')

  const from = await at(page, 60, 110)
  const to = await at(page, 460, 215)
  await page.mouse.move(from.x, from.y)
  await page.mouse.down()
  await page.mouse.move(to.x, to.y, { steps: 6 })
  await page.mouse.up()

  const table = page.locator('.te-tablefix-table')
  await expect(table).toHaveCount(1)
  await expect(table.locator('.te-tablefix-line.vertical')).toHaveCount(3)
  await expect(table.locator('.te-tablefix-line.horizontal')).toHaveCount(3)

  await bar.getByRole('button', { name: '완료' }).click()
  await expect(page.locator('.te-status')).toContainText('바로잡은 표 1개를 저장했습니다')
  await expect(table).toHaveCount(0)
  const grids = await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/table-grids`)).json()
  expect(grids).toHaveLength(1)

  const sheet = await excelOf(page)
  const name = cell(sheet, 'Name')
  const kim = cell(sheet, 'Kim')
  const phone = cell(sheet, 'Phone')
  const number = cell(sheet, '010-1234')
  expect(name && kim && phone && number).toBeTruthy()
  expect(kim![1]).not.toBe(name![1])
  expect(kim![2]).toBe(name![2])
  expect(number![1]).toBe(kim![1])
  expect(phone![1]).toBe(name![1])
})

test('a border is put in, moved and taken out, and the page goes back to its own tables', async ({ page }) => {
  const documentId = await openForm(page)
  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: '표 칸 바로잡기…' }).click()
  const bar = page.getByRole('toolbar', { name: '표 칸 바로잡기' })
  const from = await at(page, 60, 110)
  const to = await at(page, 460, 215)
  await page.mouse.move(from.x, from.y)
  await page.mouse.down()
  await page.mouse.move(to.x, to.y, { steps: 6 })
  await page.mouse.up()
  const table = page.locator('.te-tablefix-table')
  const vertical = table.locator('.te-tablefix-line.vertical')
  await expect(vertical).toHaveCount(3)

  // Put one in.
  await bar.getByRole('button', { name: '세로선 넣기' }).click()
  const spot = await at(page, 420, 160)
  await page.mouse.click(spot.x, spot.y)
  await expect(vertical).toHaveCount(4)

  // Move the new one (the third of four, left to right) and take it out with a double click.
  await bar.getByRole('button', { name: '선 옮기기' }).click()
  const line = vertical.nth(2)
  const box = (await line.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 10, box.y + box.height / 2, { steps: 3 })
  await page.mouse.up()
  expect((await line.boundingBox())!.x).toBeGreaterThan(box.x + 5)
  await line.dblclick()
  await expect(vertical).toHaveCount(3)

  await bar.getByRole('button', { name: '완료' }).click()
  await expect(page.locator('.te-status')).toContainText('바로잡은 표 1개를 저장했습니다')

  await page.locator('.te-layer').click({ button: 'right', position: { x: 5, y: 5 } })
  await page.getByRole('menuitem', { name: '표 칸 바로잡기…' }).click()
  await expect(table).toHaveCount(1)
  await bar.getByRole('button', { name: '자동으로 되돌리기' }).click()
  await expect(page.locator('.te-status')).toContainText('쪽에서 찾은 표를 다시 씁니다')
  const grids = await (await page.request.get(`/api/v1/pdf/documents/${documentId}/pages/0/table-grids`)).json()
  expect(grids).toEqual([])
})
