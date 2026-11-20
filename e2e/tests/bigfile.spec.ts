import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

/** A scan of `pages` pages, each the same 300 dpi A4 picture: about 5 MB a page, as a copier makes them. */
function scanOfPages(jpeg: Buffer, pages: number): Buffer {
  const parts: Buffer[] = []
  let length = 0
  const offsets: number[] = []
  const add = (piece: Buffer | string) => {
    const b = typeof piece === 'string' ? Buffer.from(piece, 'latin1') : piece
    parts.push(b)
    length += b.length
  }
  const object = (id: number, ...body: (Buffer | string)[]) => {
    offsets[id] = length
    add(`${id} 0 obj\n`)
    body.forEach(add)
    add('\nendobj\n')
  }
  add('%PDF-1.4\n')
  const draw = 'q 595 0 0 842 0 0 cm /Im1 Do Q'
  object(1, '<< /Type /Catalog /Pages 2 0 R >>')
  const kids = Array.from({ length: pages }, (_, i) => `${3 + i * 3} 0 R`).join(' ')
  object(2, `<< /Type /Pages /Kids [${kids}] /Count ${pages} >>`)
  for (let i = 0; i < pages; i++) {
    const id = 3 + i * 3
    object(id, `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents ${id + 1} 0 R /Resources << /XObject << /Im1 ${id + 2} 0 R >> >> >>`)
    object(id + 1, `<< /Length ${draw.length} >>\nstream\n${draw}\nendstream`)
    object(id + 2, `<< /Type /XObject /Subtype /Image /Width 2480 /Height 3508 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`, jpeg, '\nendstream')
  }
  const count = 3 + pages * 3
  const xref = length
  add(`xref\n0 ${count}\n0000000000 65535 f \n`)
  for (let id = 1; id < count; id++) add(`${String(offsets[id]).padStart(10, '0')} 00000 n \n`)
  add(`trailer\n<< /Size ${count} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`)
  return Buffer.concat(parts)
}

test('a file of about 150 MB (over the old 100 MB) opens, shows its pages and is saved', async ({ page }) => {
  test.setTimeout(240_000)
  await page.goto('/')
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 2480
    canvas.height = 3508
    const g = canvas.getContext('2d')!
    const pixels = g.createImageData(canvas.width, canvas.height)
    for (let i = 0; i < pixels.data.length; i += 4) {
      const grain = Math.random() * 120
      pixels.data[i] = 120 + grain
      pixels.data[i + 1] = 110 + grain
      pixels.data[i + 2] = 100 + grain
      pixels.data[i + 3] = 255
    }
    g.putImageData(pixels, 0, 0)
    return canvas.toDataURL('image/jpeg', 0.97)
  })
  const jpeg = Buffer.from(dataUrl.split(',')[1], 'base64')
  const pages = Math.ceil((150 * 1024 * 1024) / jpeg.length)
  const pdf = scanOfPages(jpeg, pages)
  expect(pdf.length).toBeGreaterThan(140 * 1024 * 1024)
  expect(pdf.length).toBeLessThan(200 * 1024 * 1024)

  const folder = mkdtempSync(join(tmpdir(), 'wintech-big-'))
  try {
    const path = join(folder, '준공도서.pdf')
    writeFileSync(path, pdf)
    // Through the screen, as a user opens it (its own size check first, then the server's).
    await page.locator('input[type=file]').first().setInputFiles(path)
    await expect(page.locator('.page-card')).toHaveCount(pages, { timeout: 120_000 })
    await expect(page.getByText(/파일이 너무 큽니다/)).toHaveCount(0)
    await expect(page.locator('.page-card img').first()).toBeVisible({ timeout: 60_000 })

    // Saved as it is: every page, nothing lost.
    const documentId = /documents\/([^/]+)\//.exec((await page.locator('.page-card img').first().getAttribute('src')) ?? '')![1]
    const saved = await page.request.post('/api/v1/pdf/export', {
      data: { pages: Array.from({ length: pages }, (_, i) => ({ documentId, pageIndex: i, rotation: 0 })), fileName: 'big.pdf' },
      timeout: 120_000,
    })
    expect(saved.status()).toBe(200)
    expect((await saved.body()).length).toBeGreaterThan(140 * 1024 * 1024)
  } finally {
    rmSync(folder, { recursive: true, force: true })
  }
})
