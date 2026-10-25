import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { inflateRawSync } from 'node:zlib'

const SAMPLE = join(__dirname, 'files', 'sample.hwp')

/** The text of one entry of a ZIP file (an HWPX), read straight from its central directory. */
function zipEntry(zip: Buffer, name: string): string | null {
  const end = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
  let at = zip.readUInt32LE(end + 16)
  for (let i = zip.readUInt16LE(end + 10); i > 0; i--) {
    const method = zip.readUInt16LE(at + 10)
    const size = zip.readUInt32LE(at + 20)
    const nameLength = zip.readUInt16LE(at + 28)
    const extra = zip.readUInt16LE(at + 30)
    const comment = zip.readUInt16LE(at + 32)
    const local = zip.readUInt32LE(at + 42)
    if (zip.toString('utf8', at + 46, at + 46 + nameLength) === name) {
      const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28)
      const data = zip.subarray(start, start + size)
      return (method === 8 ? inflateRawSync(data) : data).toString('utf8')
    }
    at += 46 + nameLength + extra + comment
  }
  return null
}

test('a Hangul document opens in the HWP editor, is edited and saved as HWPX and HWP, all on this PC', async ({ page }) => {
  const outside: string[] = []
  page.on('request', (request) => {
    const host = new URL(request.url()).hostname
    if (!['localhost', '127.0.0.1'].includes(host) && !request.url().startsWith('blob:') && !request.url().startsWith('data:')) {
      outside.push(request.url())
    }
  })
  await page.goto('/')
  await page.locator('input[type=file]').setInputFiles(SAMPLE)

  const view = page.getByRole('dialog', { name: '한글 문서 편집' })
  await expect(view.locator('.te-title')).toContainText(/sample\.hwp · \d+쪽/, { timeout: 60_000 })
  const studio = page.frameLocator('[data-testid=hwp-host] iframe')
  await expect(studio.locator('#studio-root')).toBeVisible()

  // Type into the document: a click on the page puts the caret there.
  const canvas = studio.locator('canvas').first()
  await expect(canvas).toBeVisible()
  const box = (await canvas.boundingBox())!
  await page.mouse.click(box.x + box.width / 2, box.y + 160)
  await page.keyboard.type('WINTECH 편집 확인')
  await page.screenshot({ path: 'test-results/hwp-editor.png' })

  const [hwpx] = await Promise.all([page.waitForEvent('download'), view.getByRole('button', { name: 'HWPX로 저장' }).click()])
  expect(hwpx.suggestedFilename()).toBe('sample.hwpx')
  const zip = readFileSync(await hwpx.path())
  expect(zip.subarray(0, 2).toString('latin1')).toBe('PK')
  expect(zipEntry(zip, 'Contents/section0.xml')).toContain('WINTECH 편집 확인')
  await expect(view.locator('.te-status')).toContainText('sample.hwpx(으)로 저장했습니다')

  const [hwp] = await Promise.all([page.waitForEvent('download'), view.getByRole('button', { name: 'HWP로 저장' }).click()])
  expect(hwp.suggestedFilename()).toBe('sample.hwp')
  // An HWP file is a compound file: D0 CF 11 E0.
  expect(readFileSync(await hwp.path()).subarray(0, 4).toString('hex')).toBe('d0cf11e0')

  // Saved, so it closes without asking.
  await view.getByRole('button', { name: /페이지 목록/ }).click()
  await expect(view).toBeHidden()
  expect(outside).toEqual([])
})
