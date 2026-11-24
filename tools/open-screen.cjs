// Opens the app's screen in Edge for a few seconds and closes it, as a user does: for tools/measure-memory.ps1.
// usage: node open-screen.cjs <url> <seconds>
const { chromium } = require(require.resolve('playwright', { paths: [__dirname + '/../e2e'] }))
;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const page = await browser.newPage()
  await page.goto(process.argv[2])
  await page.waitForTimeout(Number(process.argv[3] ?? 8) * 1000)
  await browser.close()
})()
