import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { simplePdf } from './fixtures'

/** The numbers of the picked pages, in list order. */
async function picked(page: Page): Promise<number[]> {
  const flags = await page.locator('.page-card').evaluateAll((all) => all.map((c) => c.classList.contains('selected')))
  return flags.flatMap((on, i) => (on ? [i + 1] : []))
}

async function openSix(page: Page) {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'six.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Page'], 6) })
  await expect(page.locator('.page-card')).toHaveCount(6)
}

/** The middle of a card's picture (not its buttons or tick box). */
async function middle(page: Page, index: number) {
  const r = (await page.locator('.page-card').nth(index).locator('.thumbnail-frame').boundingBox())!
  return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
}

test('pages are picked with clicks as in the file explorer: click, Ctrl+click, Shift+click; a stray click keeps them', async ({ page }) => {
  await openSix(page)
  const at = async (i: number, modifiers: ('Control' | 'Shift')[] = []) => {
    const p = await middle(page, i)
    for (const key of modifiers) await page.keyboard.down(key)
    await page.mouse.click(p.x, p.y)
    for (const key of modifiers) await page.keyboard.up(key)
  }
  await at(1)
  expect(await picked(page)).toEqual([2])
  await at(3, ['Control'])
  expect(await picked(page)).toEqual([2, 4])
  await at(1, ['Control'])
  expect(await picked(page)).toEqual([4])
  // Shift+click picks from the page clicked last (as in the file explorer).
  await at(3)
  await at(5, ['Shift'])
  expect(await picked(page)).toEqual([4, 5, 6])
  await at(0, ['Control'])
  await at(2, ['Control', 'Shift'])
  expect(await picked(page)).toEqual([1, 2, 3, 4, 5, 6])
  await at(4)
  expect(await picked(page)).toEqual([5])
  // A stray click on empty space (the margin beside the list) leaves the picked pages as they are.
  const grid = (await page.locator('.page-grid').boundingBox())!
  await page.mouse.click(grid.x - 30, (await middle(page, 0)).y)
  expect(await picked(page)).toEqual([5])
  // The tick box still works on its own.
  await page.locator('.page-card').nth(2).locator('input[type=checkbox]').check()
  expect(await picked(page)).toEqual([3, 5])
})

test('a box dragged from empty space or with Ctrl from a card picks the pages it touches; a plain drag on a card moves it', async ({ page }) => {
  await openSix(page)
  const grid = (await page.locator('.page-grid').boundingBox())!
  const cards = page.locator('.page-card')
  const card = async (i: number) => (await cards.nth(i).boundingBox())!

  // From the margin left of the list, right over the 1st to 3rd cards.
  const first = await card(0)
  const third = await card(2)
  await page.mouse.move(grid.x - 30, first.y + 20)
  await page.mouse.down()
  await page.mouse.move(grid.x + 30, first.y + 60, { steps: 4 })
  await expect(page.locator('.page-band')).toBeVisible()
  await page.mouse.move(third.x + third.width / 2, first.y + first.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(page.locator('.page-band')).toHaveCount(0)
  expect(await picked(page)).toEqual([1, 2, 3])
  await expect(cards.first().locator('img')).toHaveAttribute('src', /pages\/0\//) // nothing moved

  // With Ctrl, from a card: the box adds the 5th and 6th.
  const fifth = await middle(page, 4)
  const sixth = await card(5)
  await page.keyboard.down('Control')
  await page.mouse.move(fifth.x, fifth.y)
  await page.mouse.down()
  await page.mouse.move(sixth.x + sixth.width - 10, fifth.y + 30, { steps: 8 })
  await page.mouse.up()
  await page.keyboard.up('Control')
  expect(await picked(page)).toEqual([1, 2, 3, 5, 6])
  await expect(cards.nth(4).locator('img')).toHaveAttribute('src', /pages\/4\//) // nothing moved

  // From the empty space after the last card; Esc while dragging puts the choice back.
  await page.mouse.move(grid.x + grid.width - 20, fifth.y)
  await page.mouse.down()
  await page.mouse.move(first.x + 20, fifth.y + 10, { steps: 8 })
  await expect(page.locator('.page-band')).toBeVisible()
  await page.keyboard.press('Escape')
  await page.mouse.up()
  expect(await picked(page)).toEqual([1, 2, 3, 5, 6])

  // A plain drag on a card moves the page, as before: the 4th (not picked) after the 6th.
  const fourth = await middle(page, 3)
  await page.mouse.move(fourth.x, fourth.y)
  await page.mouse.down()
  await page.mouse.move(sixth.x + sixth.width - 10, sixth.y + sixth.height / 2, { steps: 10 })
  await expect(page.locator('.page-band')).toHaveCount(0)
  await page.mouse.up()
  await expect(cards.last().locator('img')).toHaveAttribute('src', /pages\/3\//)
})

test('the picked pages stay picked when the window is scrolled (scroll bar, wheel)', async ({ page }) => {
  await page.setViewportSize({ width: 1400, height: 700 })
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'many.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Page'], 18) })
  await expect(page.locator('.page-card')).toHaveCount(18)
  for (const i of [0, 2]) await page.locator('.page-card').nth(i).locator('input[type=checkbox]').check()
  expect(await picked(page)).toEqual([1, 3])

  // The scroll bar: a press on it reaches the page as a press on the page's root (<html>), at its right edge.
  // The test browser draws no scroll bar, so the press is sent as the browser sends it.
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 2))
  await page.evaluate(() => {
    const root = document.documentElement
    const x = window.innerWidth - 8
    const y = 300
    for (const [type, buttons] of [['pointerdown', 1], ['pointermove', 1], ['pointermove', 1], ['pointerup', 0]] as const) {
      const moved = type === 'pointermove' ? 120 : 0
      root.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId: 1, pointerType: 'mouse', button: 0, buttons, clientX: x, clientY: y + moved }))
    }
    window.scrollBy(0, 400)
  })
  await expect(page.locator('.page-band')).toHaveCount(0)
  expect(await picked(page)).toEqual([1, 3])

  // The wheel.
  await page.mouse.move(700, 400)
  await page.mouse.wheel(0, 600)
  await page.mouse.wheel(0, -900)
  expect(await picked(page)).toEqual([1, 3])
  // A click on the empty margin beside the list, as before scrolling.
  const grid = (await page.locator('.page-grid').boundingBox())!
  await page.mouse.click(grid.x - 30, 400)
  expect(await picked(page)).toEqual([1, 3])
})
