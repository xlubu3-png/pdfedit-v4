import { expect, test } from '@playwright/test'

test('a photo and a screenshot are opened as pages of their own, like a scan', async ({ page }) => {
  await page.goto('/')
  const picture = async (type: string, w: number, h: number) =>
    Buffer.from(
      (
        await page.evaluate(
          ([t, width, height]) => {
            const canvas = document.createElement('canvas')
            canvas.width = width as number
            canvas.height = height as number
            const g = canvas.getContext('2d')!
            g.fillStyle = '#fff'
            g.fillRect(0, 0, canvas.width, canvas.height)
            g.fillStyle = '#000'
            g.font = '48px sans-serif'
            g.fillText('견적서 합계 240,070원', 80, 200)
            return canvas.toDataURL(t as string, 0.9)
          },
          [type, w, h] as const,
        )
      ).split(',')[1],
      'base64',
    )
  await page.locator('input[type=file]').first().setInputFiles([
    { name: '사진.jpg', mimeType: 'image/jpeg', buffer: await picture('image/jpeg', 1240, 1754) },
    { name: '화면.png', mimeType: 'image/png', buffer: await picture('image/png', 1600, 900) },
  ])
  await expect(page.locator('.page-card')).toHaveCount(2)
  await expect(page.getByText(/그림 파일을 PDF로 바꿔 열었습니다/)).toBeVisible()
  await expect(page.locator('.page-card').first()).toContainText('사진.pdf')
  await expect(page.locator('.page-card img').first()).toBeVisible()
})
