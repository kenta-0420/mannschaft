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

test('deの組織予定の見出し・追加操作・未選択案内を翻訳する', async ({ page }) => {
  test.setTimeout(180_000)
  const slug = process.env.SCHEDULE_I18N_ORG_SLUG
  if (!slug) throw new Error('SCHEDULE_I18N_ORG_SLUGに専用fixtureが必要です')
  await page.goto(`/organizations/${encodeURIComponent(slug)}/schedule`, { waitUntil: 'domcontentloaded' })
  const heading = page.locator('main h1').last()
  const add = page.getByRole('button').filter({ has: page.locator('.pi-plus') }).first()
  const empty = page.locator('div.py-8').filter({ has: page.locator('i.pi-calendar') }).locator('p').first()
  await expect(heading).toBeVisible({ timeout: 120_000 })
  await expect(add).toBeVisible()
  await expect(empty).toBeVisible()
  expect.soft((await heading.textContent())?.trim()).toBe('Terminplan')
  expect.soft((await add.textContent())?.trim()).toBe('Termin hinzufügen')
  expect.soft((await empty.textContent())?.trim()).toBe('Bitte einen Termin auswählen')
})

test('deの個人カレンダーのタブ・未選択案内・個人予定の色を翻訳する', async ({ page }) => {
  test.setTimeout(180_000)
  // 未選択サイドパネルはデスクトップだけに描画される。
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
  const tab = page.getByRole('button').filter({ has: page.locator('i.pi-calendar') }).first()
  const empty = page.locator('div.py-8').filter({ has: page.locator('i.pi-calendar') }).locator('p').first()
  await expect(tab).toBeVisible({ timeout: 120_000 })
  await expect(empty).toBeVisible()
  expect.soft((await tab.textContent())?.trim()).toBe('Kalender')
  expect.soft((await empty.textContent())?.trim()).toBe('Bitte ein Datum oder einen Termin auswählen')
  await page.getByRole('button', { name: 'Termin hinzufügen', exact: true }).click()
  const form = page.getByRole('dialog').filter({ has: page.getByTestId('schedule-submit') })
  await expect(form).toBeVisible()
  await expect(form.locator('button[style*="background-color"]').first()).toBeVisible()
  expect.soft((await form.locator('label').last().textContent())?.trim()).toBe('Farbe')
})

for (const scope of ['team', 'organization', 'calendar'] as const) {
  test(`deの${scope}予定の削除確認を翻訳し、キャンセル時にDELETEを送らない`, async ({ page }) => {
    test.setTimeout(180_000)
    const slug = process.env[scope === 'organization' ? 'SCHEDULE_I18N_ORG_SLUG' : 'SCHEDULE_I18N_TEAM_SLUG']
    const id = process.env[scope === 'organization' ? 'SCHEDULE_I18N_ORG_SCHEDULE_ID' : 'SCHEDULE_I18N_TEAM_SCHEDULE_ID']
    if (!slug || !id) throw new Error('専用fixtureのscopeと予定IDが必要です')
    const url = scope === 'calendar'
      ? `/calendar?scheduleId=${encodeURIComponent(id)}`
      : `/${scope === 'team' ? 'teams' : 'organizations'}/${encodeURIComponent(slug)}/schedule?eventId=${encodeURIComponent(id)}`
    const deletes: string[] = []
    page.on('request', request => {
      if (request.method() === 'DELETE' && new URL(request.url()).pathname.includes('/schedules/')) {
        deletes.push(new URL(request.url()).pathname)
      }
    })
    await page.goto(url, { waitUntil: 'domcontentloaded' })
    const remove = page.getByRole('button', { name: 'Löschen', exact: true }).filter({ has: page.locator('.pi-trash') }).first()
    await expect(remove).toBeVisible({ timeout: 120_000 })
    const dialogPromise = page.waitForEvent('dialog')
    const clickPromise = remove.click()
    const confirmation = await dialogPromise
    const message = confirmation.message()
    await confirmation.dismiss()
    await clickPromise
    expect.soft(message).toBe('Diesen Termin löschen?')
    expect(deletes).toEqual([])
    await expect(remove).toBeVisible()
  })
}
