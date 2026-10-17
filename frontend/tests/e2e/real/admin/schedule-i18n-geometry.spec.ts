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
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
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
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
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
    await expect(page.locator('html')).toHaveAttribute('lang', 'de')
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

for (const width of [360, 390, 1440]) {
  test(`予定周辺のtap領域が${width}pxで44px以上になる`, async ({ page }, testInfo) => {
    test.setTimeout(240_000)
    const slug = process.env.SCHEDULE_I18N_TEAM_SLUG
    const id = process.env.SCHEDULE_I18N_TEAM_SCHEDULE_ID
    if (!slug || !id) throw new Error('専用fixtureのteamと予定IDが必要です')
    await page.setViewportSize({ width, height: 900 })
    const measure = async (name: string, target: import('@playwright/test').Locator) => {
      await expect(target).toBeVisible({ timeout: 120_000 })
      await page.evaluate(() => document.fonts.ready)
      const box = await target.boundingBox()
      await testInfo.attach(`${name}-${width}.json`, {
        body: JSON.stringify({ width, name, box }), contentType: 'application/json',
      })
      expect.soft(box?.height, `${name}の高さ`).toBeGreaterThanOrEqual(44)
      expect.soft(box?.width, `${name}の幅`).toBeGreaterThanOrEqual(44)
    }
    await page.goto(`/teams/${encodeURIComponent(slug)}/schedule?eventId=${encodeURIComponent(id)}`, { waitUntil: 'domcontentloaded' })
    await measure('keep', page.getByTestId('schedule-keep-nav-link'))
    const calendarHeader = page.locator('h2.text-lg.font-extrabold').first().locator('..')
    await measure('calendar-prev', calendarHeader.getByRole('button').filter({ has: page.locator('.pi-chevron-left') }))
    await measure('calendar-next', calendarHeader.getByRole('button').filter({ has: page.locator('.pi-chevron-right') }))
    await measure('record', page.getByRole('button').filter({ has: page.locator('.pi-play') }).first())
    const input = page.getByTestId('schedule-comment-input').first()
    await expect(input).toBeVisible()
    await input.fill('Geometry check (not submitted)')
    await measure('comment-post', page.getByTestId('schedule-comment-submit').first())
    if (width < 768) {
      const nextTab = page.locator('.p-tablist-next-button').first()
      await measure('tablist-next', nextTab)
      await nextTab.click()
      await measure('tablist-prev', page.locator('.p-tablist-prev-button').first())
    }
    expect.soft(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)

    // 通信失敗注入。権限と予定取得は実APIのまま、委任一覧だけを中断する。
    let interrupted = 0
    const delegationUrl = (url: URL) => url.pathname === `/api/v1/schedules/${id}/delegations`
    await page.route(delegationUrl, async route => { interrupted++; await route.abort() })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await measure('delegation-error-retry', page.getByTestId('schedule-delegation-error-retry'))
    expect(interrupted).toBe(1)
    await page.unroute(delegationUrl)

    await page.goto('/teams/surroundings-missing-261017/schedule', { waitUntil: 'domcontentloaded' })
    await measure('shell-retry', page.getByRole('button').filter({ has: page.locator('.pi-refresh') }).first())

    expect.soft(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  })
}
