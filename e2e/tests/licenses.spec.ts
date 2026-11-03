import { expect, test } from '@playwright/test'

test('the open source licenses open from the first screen, with every part and its license text', async ({ page, context }) => {
  await page.goto('/')

  const [notices] = await Promise.all([
    context.waitForEvent('page'),
    page.getByRole('link', { name: '오픈소스 라이선스' }).click(),
  ])
  await notices.waitForLoadState()
  const text = await notices.locator('body').innerText()

  // Korean read as UTF-8, the parts the program holds, and the license texts themselves.
  expect(text).toContain('WINTECH 문서편집기 오픈소스 고지')
  for (const part of ['rhwp-studio', 'org.apache.pdfbox:pdfbox', 'com.microsoft.onnxruntime:onnxruntime', 'react 19', 'PaddleOCR']) {
    expect(text).toContain(part)
  }
  expect(text).toContain('Copyright (c) 2025-2026 Edward Kim')
  expect(text).toContain('Apache License')
  expect(text).toContain('Apache PDFBox')
  expect(text).toContain('SIL OPEN FONT LICENSE')

  // The Hangul editor's own license file is there as well.
  const rhwp = await page.request.get('/hwp-studio/LICENSE-rhwp.txt')
  expect(await rhwp.text()).toContain('MIT License')
})
