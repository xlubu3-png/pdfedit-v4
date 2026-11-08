import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

test('문제 보고 saves the logs and a page about the PC as one ZIP, and says where it went', async ({ page }) => {
  await page.goto('/')
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '문제 보고' }).click()])
  expect(download.suggestedFilename()).toMatch(/^WINTECH_문제보고_\d{8}_\d{4}\.zip$/)
  const zip = readFileSync((await download.path())!)
  expect(zip.subarray(0, 2).toString()).toBe('PK')
  expect(zip.toString('utf8')).toContain('PC와 프로그램 정보.txt')
  await expect(page.locator('.info-banner[role=status]').last()).toContainText('문제 보고 파일을 내려받았습니다')
})
