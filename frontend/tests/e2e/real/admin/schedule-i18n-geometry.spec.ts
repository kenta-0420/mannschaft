import { expect, test } from '@playwright/test'

/**
 * CMP-261017-0854 AC-6。APIモックなし・deの管理者fixtureで実予定画面を検査する。
 * 専用runnerが既存実機セッション終了後に一度だけ認証し、非公開storageStateを渡す。
 * このspecからログイン・アカウント変更・予定データ変更は行わない。
 */
test('deの予定追加ボタンが360/390pxの画面内に収まり、ページが横へ伸びない', async ({
  page,
  baseURL,
}, testInfo) => {
  const teamSlug = process.env.SCHEDULE_I18N_TEAM_SLUG
  if (!teamSlug) throw new Error('SCHEDULE_I18N_TEAM_SLUGに専用実機fixtureのslugが必要です')

  const apiBase = process.env.API_BASE_URL
  if (!baseURL || !apiBase) throw new Error('専用実機のbaseURLとAPI_BASE_URLが必要です')
  const allowedOrigins = new Set([new URL(baseURL).origin, new URL(apiBase).origin])
  const blockedOrigins = new Set<string>()
  // 外部送信だけを停止する。アプリとAPIは実通信のままで、応答をモックしない。
  await page.context().route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (['data:', 'blob:'].includes(url.protocol) || allowedOrigins.has(url.origin)) {
      await route.continue()
      return
    }
    await route.abort()
    if (!blockedOrigins.has(url.origin)) {
      blockedOrigins.add(url.origin)
      await testInfo.attach('blocked-origin.json', {
        body: JSON.stringify({ origin: url.origin }),
        contentType: 'application/json',
      })
    }
  })
  await page.goto(`/teams/${encodeURIComponent(teamSlug)}/schedule`, {
    waitUntil: 'domcontentloaded',
  })
  await expect(page.getByRole('heading', { name: 'Terminplan', exact: true })).toBeVisible({
    timeout: 60_000,
  })
  const addButton = page.getByRole('button', { name: 'Termin hinzufügen', exact: true })
  await expect(addButton).toBeVisible()

  for (const width of [360, 390]) {
    await test.step(`${width}pxの実geometry`, async () => {
      await page.setViewportSize({ width, height: 844 })
      await page.evaluate(async () => {
        await document.fonts.ready
      })
      const laterButton = page.getByRole('button', { name: 'Später entscheiden', exact: true })
      if (await laterButton.isVisible()) {
        await laterButton.click()
        await expect(laterButton).toBeHidden()
      }
      // 実クリックせず、dialogに覆われず操作できることを確認する。
      await addButton.click({ trial: true })
      const geometry = await addButton.evaluate((button) => {
        const rect = button.getBoundingClientRect()
        const root = document.documentElement
        return {
          clientWidth: root.clientWidth,
          scrollWidth: root.scrollWidth,
          clientHeight: root.clientHeight,
          width: rect.width,
          height: rect.height,
          left: rect.left,
          right: rect.right,
          top: rect.top,
          bottom: rect.bottom,
        }
      })
      await testInfo.attach(`geometry-${width}.json`, {
        body: JSON.stringify(geometry),
        contentType: 'application/json',
      })
      await page.screenshot({
        path: testInfo.outputPath(`de-schedule-${width}.png`),
        fullPage: true,
      })

      expect
        .soft(geometry.scrollWidth, `${width}pxでページ横パンがない`)
        .toBeLessThanOrEqual(geometry.clientWidth)
      expect.soft(geometry.width, `${width}pxで追加ボタン幅が44px以上`).toBeGreaterThanOrEqual(44)
      expect
        .soft(geometry.height, `${width}pxで追加ボタン高さが44px以上`)
        .toBeGreaterThanOrEqual(44)
      expect.soft(geometry.left, `${width}pxで追加ボタンの左端が画面内`).toBeGreaterThanOrEqual(0)
      expect
        .soft(geometry.right, `${width}pxで追加ボタンの右端が画面内`)
        .toBeLessThanOrEqual(geometry.clientWidth)
      expect.soft(geometry.top, `${width}pxで追加ボタンの上端が画面内`).toBeGreaterThanOrEqual(0)
      expect
        .soft(geometry.bottom, `${width}pxで追加ボタンの下端が画面内`)
        .toBeLessThanOrEqual(geometry.clientHeight)
    })
  }
})
