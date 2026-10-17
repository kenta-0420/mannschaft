import { expect, test } from '@playwright/test'

/**
 * CMP-261017-0854 AC-6。APIモックなし・deの管理者fixtureで実予定画面を検査する。
 * 専用runnerが既存実機セッション終了後に一度だけ認証し、非公開storageStateを渡す。
 * このspecからログイン・アカウント変更・予定データ変更は行わない。
 */
test('deの予定追加ボタンが360/390pxの画面内に収まり、ページが横へ伸びない', async ({
  page,
}, testInfo) => {
  const teamSlug = process.env.SCHEDULE_I18N_TEAM_SLUG
  if (!teamSlug) throw new Error('SCHEDULE_I18N_TEAM_SLUGに専用実機fixtureのslugが必要です')

  await page.goto(`/teams/${encodeURIComponent(teamSlug)}/schedule`, {
    waitUntil: 'domcontentloaded',
  })
  await expect(page.getByRole('heading', { name: 'Terminplan', exact: true })).toBeVisible()
  const addButton = page.getByRole('button', { name: 'Termin hinzufügen', exact: true })
  await expect(addButton).toBeVisible()

  for (const width of [360, 390]) {
    await test.step(`${width}pxの実geometry`, async () => {
      await page.setViewportSize({ width, height: 844 })
      await page.evaluate(async () => {
        await document.fonts.ready
      })
      const geometry = await addButton.evaluate((button) => {
        const rect = button.getBoundingClientRect()
        const root = document.documentElement
        return {
          clientWidth: root.clientWidth,
          scrollWidth: root.scrollWidth,
          clientHeight: root.clientHeight,
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
