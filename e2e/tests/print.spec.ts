import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { simplePdf } from './fixtures'

/** Records what is printed instead of opening the print dialog: the PDF handed to the hidden frame. */
async function catchPrints(page: Page) {
  await page.addInitScript(() => {
    const printed: string[] = []
    ;(window as unknown as { printed: string[] }).printed = printed
    const original = HTMLIFrameElement.prototype.addEventListener
    HTMLIFrameElement.prototype.addEventListener = function (this: HTMLIFrameElement, type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions) {
      if (type === 'load' && this.classList.contains('print-frame')) {
        const frame = this
        return original.call(this, type, () => {
          Object.defineProperty(frame, 'contentWindow', { value: { focus() {}, print() { printed.push(frame.src) } } })
          ;(listener as () => void)()
        }, options)
      }
      return original.call(this, type, listener, options)
    }
  })
}

async function printedPdf(page: Page): Promise<string> {
  await expect.poll(() => page.evaluate(() => (window as unknown as { printed: string[] }).printed.length)).toBe(1)
  const url = await page.evaluate(() => (window as unknown as { printed: string[] }).printed[0])
  expect(url).toMatch(/^blob:/)
  return page.evaluate(async (blobUrl) => (await (await fetch(blobUrl)).text()).slice(0, 5), url)
}

test('the page list prints its pages, as they would be exported', async ({ page }) => {
  await catchPrints(page)
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'p.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Print me'], 2) })
  await expect(page.getByText('2페이지')).toBeVisible()

  const exported = page.waitForRequest((r) => r.url().endsWith('/api/v1/pdf/export') && r.method() === 'POST')
  await page.getByRole('button', { name: '인쇄', exact: true }).click()

  expect((await exported).postDataJSON().pages).toHaveLength(2)
  expect(await printedPdf(page)).toBe('%PDF-')
})

test('the editor prints with Ctrl+P', async ({ page }) => {
  await catchPrints(page)
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles({ name: 'p.pdf', mimeType: 'application/pdf', buffer: simplePdf(['Print me']) })
  await page.locator('button[title="텍스트 편집"]').first().click()
  await expect(page.locator('.te-box[data-index="0"]')).toBeVisible()

  await page.keyboard.press('Control+p')

  expect(await printedPdf(page)).toBe('%PDF-')
})
