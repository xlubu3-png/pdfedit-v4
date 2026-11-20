import { expect, test } from '@playwright/test'
import { simplePdf } from './fixtures'

test('a box dragged from an empty part of the page list picks the pages it touches; Ctrl adds, Esc gives it up', async ({ page }) => {
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'six.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Page'], 6) })
  const cards = page.locator('.page-card')
  await expect(cards).toHaveCount(6)
  const box = async (i: number) => (await cards.nth(i).boundingBox())!
  const grid = (await page.locator('.page-grid').boundingBox())!

  // From the empty space after the last card (right of the row), left over the 5th and 6th cards.
  const second = await box(4)
  const sixthCard = await box(5)
  const startX = grid.x + grid.width - 20
  const startY = sixthCard.y + sixthCard.height / 2
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  await page.mouse.move(startX - 20, startY - 30, { steps: 3 })
  await expect(page.locator('.page-band')).toBeVisible()
  await page.mouse.move(second.x + second.width / 2, second.y + second.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(page.locator('.page-band')).toHaveCount(0)
  const picked = async () => (await cards.evaluateAll((all) => all.map((c, i) => (c.classList.contains('selected') ? i + 1 : 0)))).filter((n) => n > 0)
  const firstPick = await picked()
  expect(firstPick).toEqual([5, 6])
  // The pages stay where they were: a box picks, it does not move pages.
  await expect(cards.first()).toContainText('1')

  // With Ctrl a box adds to the pages already picked: from the gap between the 1st and 2nd card, over the 1st.
  const first = await box(0)
  const secondCard = await box(1)
  const gapX = (first.x + first.width + secondCard.x) / 2
  await page.keyboard.down('Control')
  await page.mouse.move(gapX, first.y + 20)
  await page.mouse.down()
  await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2, { steps: 8 })
  await page.mouse.up()
  await page.keyboard.up('Control')
  expect(await picked()).toEqual([1, 5, 6])

  // Without Ctrl a new box starts a new choice.
  await page.mouse.move(gapX, first.y + 20)
  await page.mouse.down()
  await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2, { steps: 8 })
  await page.mouse.up()
  expect(await picked()).toEqual([1])

  // Esc while dragging puts the choice back as it was.
  const before = await picked()
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  await page.mouse.move(second.x + 10, second.y + 10, { steps: 8 })
  await page.keyboard.press('Escape')
  await page.mouse.up()
  expect(await picked()).toEqual(before)

  // A press on a card still moves the page, as before (no box): the 1st page dragged after the 2nd.
  await page.mouse.move(first.x + first.width / 2, first.y + first.height / 2)
  await page.mouse.down()
  await page.mouse.move(secondCard.x + secondCard.width - 10, secondCard.y + secondCard.height / 2, { steps: 10 })
  await expect(page.locator('.page-band')).toHaveCount(0)
  await page.mouse.up()
  await expect(cards.first().locator('img')).toHaveAttribute('src', /pages\/1\//)
})
