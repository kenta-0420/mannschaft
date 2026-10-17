import { expect, test, type Locator, type Page } from '@playwright/test'

test.describe('calendar-mobile-header: 真mobileの見出しと詳細操作', () => {
  test.use({ isMobile: true, hasTouch: true })

  test('360pxと390pxで操作が画面内に収まり、予定詳細を閉じられる', async ({ page }, testInfo) => {
    test.setTimeout(180_000)
    const locale = process.env.I18N_TAP_LOCALE === 'en' ? 'en' : 'de'
    const id = process.env.SCHEDULE_I18N_ORG_SCHEDULE_ID
    if (!id) throw new Error('専用fixtureの組織予定IDが必要です')
    const writes: string[] = []
    page.on('request', request => {
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && new URL(request.url()).pathname.includes('/schedules')) writes.push(request.method())
    })
    for (const width of [360, 390]) {
      await page.setViewportSize({ width, height: 800 })
      await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
      const add = page.getByRole('button', { name: locale === 'en' ? 'Add event' : 'Termin hinzufügen', exact: true })
      const header = page.locator('main h1').last().locator('..')
      await readyForInteraction(page, header.locator('h1'))
      const controls = [header.locator('h1'), header.getByTestId('page-header-help'), header.getByRole('combobox'), add]
      const boxes = []
      for (const control of controls) {
        await expect(control).toBeVisible()
        const box = await control.boundingBox()
        boxes.push(box)
        expect.soft(box!.x, `${width}pxで見出し操作の左端が画面内`).toBeGreaterThanOrEqual(0)
        expect.soft(box!.x + box!.width, `${width}pxで見出し操作の右端が画面内`).toBeLessThanOrEqual(width)
      }
      const viewport = await page.evaluate(() => ({
        innerWidth: window.innerWidth,
        clientWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        visualWidth: window.visualViewport?.width,
      }))
      await testInfo.attach(`calendar-header-${width}.json`, { body: JSON.stringify({ width, boxes, viewport }), contentType: 'application/json' })
      expect.soft(viewport.innerWidth).toBeLessThanOrEqual(width)
      expect.soft(viewport.scrollWidth).toBeLessThanOrEqual(width)
      expect.soft(viewport.visualWidth!).toBeLessThanOrEqual(width + 1)

      await page.goto(`/calendar?scheduleId=${encodeURIComponent(id)}`, { waitUntil: 'domcontentloaded' })
      const detail = page.getByRole('dialog').filter({ has: page.getByTestId('schedule-edit') })
      await expect(detail).toBeVisible({ timeout: 120_000 })
      const close = detail.locator('.p-dialog-close-button')
      await expect(close).toBeVisible()
      const detailBox = await detail.boundingBox()
      const closeBox = await close.boundingBox()
      await testInfo.attach(`calendar-detail-${width}.json`, { body: JSON.stringify({ width, detailBox, closeBox }), contentType: 'application/json' })
      expect.soft(detailBox!.x).toBeGreaterThanOrEqual(0)
      expect.soft(detailBox!.x + detailBox!.width).toBeLessThanOrEqual(width)
      expect.soft(closeBox!.x).toBeGreaterThanOrEqual(0)
      expect.soft(closeBox!.x + closeBox!.width).toBeLessThanOrEqual(width)
      let closeError: string | null = null
      try {
        await close.click({ timeout: 5_000 })
        await expect(detail).toBeHidden({ timeout: 5_000 })
      }
      catch (error) {
        closeError = String(error)
        await testInfo.attach(`calendar-detail-close-${width}.png`, { body: await page.screenshot(), contentType: 'image/png' })
        await page.keyboard.press('Escape')
      }
      expect.soft(closeError, `${width}pxで実際の閉じる操作が成功する`).toBeNull()
    }
    expect(writes).toEqual([])
  })
})

/** 初期権限の案内は既存の後回し操作で閉じ、業務操作の前提だけを整える。 */
async function readyForInteraction(page: Page, target: Locator) {
  await expect(target).toBeVisible({ timeout: 120_000 })
  await expect(async () => {
    const later = page.getByRole('button', { name: /^(Später entscheiden|Decide later)$/ }).first()
    if (await later.isVisible()) {
      await later.click({ timeout: 2_000 })
      await expect(later).toBeHidden({ timeout: 2_000 })
    }
    // 遅れて現れたmaskも検知し、180秒のclick待ちへ入らない。
    await target.click({ trial: true, timeout: 1_000 })
  }).toPass({ timeout: 15_000, intervals: [250, 500, 1_000] })
}

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

test('組織予定の見出し・追加操作・未選択案内を選択言語で表示する', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const locale = process.env.I18N_TAP_LOCALE === 'en' ? 'en' : 'de'
  const labels = locale === 'en'
    ? ['Schedule', 'Add event', 'Select an event']
    : ['Terminplan', 'Termin hinzufügen', 'Bitte einen Termin auswählen']
  const slug = process.env.SCHEDULE_I18N_ORG_SLUG
  if (!slug) throw new Error('SCHEDULE_I18N_ORG_SLUGに専用fixtureが必要です')
  await page.goto(`/organizations/${encodeURIComponent(slug)}/schedule`, { waitUntil: 'domcontentloaded' })
  const heading = page.locator('main h1').last()
  const add = page.getByRole('button').filter({ has: page.locator('.pi-plus') }).first()
  const empty = page.locator('div.py-8').filter({ has: page.locator('i.pi-calendar') }).locator('p').first()
  await expect(heading).toBeVisible({ timeout: 120_000 })
  await expect(page.locator('html')).toHaveAttribute('lang', locale)
  await expect(add).toBeVisible()
  await expect(empty).toBeVisible()
  await readyForInteraction(page, add)
  expect.soft((await heading.textContent())?.trim()).toBe(labels[0])
  expect.soft((await add.textContent())?.trim()).toBe(labels[1])
  expect.soft((await empty.textContent())?.trim()).toBe(labels[2])
  for (const width of [360, 390]) {
    await page.setViewportSize({ width, height: 844 })
    await page.evaluate(() => document.fonts.ready)
    const titleBox = await heading.boundingBox()
    const addBox = await add.boundingBox()
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    const contentWidth = await add.evaluate(button => ({
      scroll: button.scrollWidth,
      client: button.clientWidth,
      label: button.querySelector('.p-button-label')?.getBoundingClientRect().toJSON(),
    }))
    await testInfo.attach(`org-header-${width}.json`, {
      body: JSON.stringify({ width, titleBox, addBox, scrollWidth, contentWidth }), contentType: 'application/json',
    })
    expect.soft(scrollWidth).toBeLessThanOrEqual(width)
    expect.soft(titleBox!.x + titleBox!.width).toBeLessThanOrEqual(width)
    expect.soft(addBox!.x + addBox!.width).toBeLessThanOrEqual(width)
    expect.soft(addBox!.height).toBeGreaterThanOrEqual(44)
    expect.soft(contentWidth.scroll, `${width}pxで追加ボタンの文字を切らない`).toBeLessThanOrEqual(contentWidth.client)
  }
})

test('date-value: 360pxと390pxの編集日付を横スクロールせず全文表示する', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const slug = process.env.SCHEDULE_I18N_TEAM_SLUG
  const id = process.env.SCHEDULE_I18N_TEAM_SCHEDULE_ID
  if (!slug || !id) throw new Error('専用fixtureのteamと予定IDが必要です')
  const writes: string[] = []
  page.on('request', request => {
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && new URL(request.url()).pathname.includes('/schedules')) writes.push(request.method())
  })
  await page.goto(`/teams/${encodeURIComponent(slug)}/schedule?eventId=${encodeURIComponent(id)}`, { waitUntil: 'domcontentloaded' })
  const edit = page.getByTestId('schedule-edit').filter({ visible: true })
  await readyForInteraction(page, edit)
  await edit.click()
  const form = page.getByRole('dialog').filter({ has: page.getByTestId('schedule-submit') })
  await expect(form).toBeVisible()
  await expect(form.locator('#schedule-start-date')).toHaveValue(/\d{4}/)
  for (const width of [360, 390]) {
    await page.setViewportSize({ width, height: 844 })
    await page.evaluate(() => document.fonts.ready)
    for (const field of ['schedule-start-date', 'schedule-end-date']) {
      const metrics = await form.locator(`#${field}`).evaluate(element => {
        const input = element as HTMLInputElement
        const style = getComputedStyle(input)
        const context = document.createElement('canvas').getContext('2d')
        if (!context) throw new Error('日付の実font幅を測定できません')
        context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
        const letterSpacing = Number.parseFloat(style.letterSpacing) || 0
        const textWidth = context.measureText(input.value).width + letterSpacing * Math.max(0, input.value.length - 1)
        const padding = Number.parseFloat(style.paddingLeft) + Number.parseFloat(style.paddingRight)
        return { value: input.value, font: context.font, textWidth, clientWidth: input.clientWidth, padding, availableWidth: input.clientWidth - padding, scrollLeft: input.scrollLeft }
      })
      await testInfo.attach(`date-${width}-${field}.json`, { body: JSON.stringify(metrics), contentType: 'application/json' })
      expect.soft(metrics.scrollLeft).toBe(0)
      expect.soft(metrics.textWidth, `${width}px ${field}の日付全文が入力欄へ収まる`).toBeLessThanOrEqual(metrics.availableWidth)
    }
  }
  await form.getByRole('button', { name: /^(Abbrechen|Cancel)$/ }).click()
  await expect(form).toBeHidden()
  expect(writes).toEqual([])
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
  const add = page.getByRole('button', { name: 'Termin hinzufügen', exact: true })
  await readyForInteraction(page, add)
  await add.click()
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
    await readyForInteraction(page, remove)
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
    const measure = async (name: string, target: Locator) => {
      await expect(target).toBeVisible({ timeout: 15_000 })
      await page.evaluate(() => document.fonts.ready)
      const box = await target.boundingBox()
      await testInfo.attach(`${name}-${width}.json`, {
        body: JSON.stringify({ width, name, box }), contentType: 'application/json',
      })
      expect.soft(box?.height, `${name}の高さ`).toBeGreaterThanOrEqual(44)
      expect.soft(box?.width, `${name}の幅`).toBeGreaterThanOrEqual(44)
    }
    await page.goto(`/teams/${encodeURIComponent(slug)}/schedule?eventId=${encodeURIComponent(id)}`, { waitUntil: 'domcontentloaded' })
    await readyForInteraction(page, page.getByTestId('schedule-keep-nav-link'))
    await measure('keep', page.getByTestId('schedule-keep-nav-link'))
    if (width < 768) {
      // mobileは既に44pxのリスト月送り。desktopのCalendarGridとは別DOM。
      await measure('mobile-month-prev', page.getByRole('button', { name: /^(Vorheriger Monat|Previous month)$/ }))
      await measure('mobile-month-next', page.getByRole('button', { name: /^(Nächster Monat|Next month)$/ }))
    } else {
      const calendarHeader = page.locator('h2.text-lg.font-extrabold').filter({ visible: true }).first().locator('..')
      await measure('calendar-prev', calendarHeader.getByRole('button').filter({ has: page.locator('.pi-chevron-left') }))
      await measure('calendar-next', calendarHeader.getByRole('button').filter({ has: page.locator('.pi-chevron-right') }))
    }
    await measure('record', page.getByRole('button').filter({ has: page.locator('.pi-play') }).first())
    const input = page.getByTestId('schedule-comment-input').filter({ visible: true }).first()
    await expect(input).toBeVisible({ timeout: 15_000 })
    await readyForInteraction(page, input)
    await input.fill('Geometry check (not submitted)')
    await measure('comment-post', page.getByTestId('schedule-comment-submit').filter({ visible: true }).first())
    const nextTab = page.locator('.p-tablist-next-button').first()
    if (width < 768) await expect(nextTab).toBeVisible()
    let movedTabs = false
    if (await nextTab.isVisible()) {
      await measure('tablist-next', nextTab)
      if (await nextTab.isEnabled()) {
        await nextTab.click()
        movedTabs = true
      }
    }
    const prevTab = page.locator('.p-tablist-prev-button').first()
    // TabListのsmooth scroll完了を待ち、直後の非表示判定でprevを落とさない。
    if (movedTabs) await expect(prevTab).toBeVisible({ timeout: 5_000 })
    if (await prevTab.isVisible()) await measure('tablist-prev', prevTab)
    await testInfo.attach(`tablist-visibility-${width}.json`, {
      body: JSON.stringify({ width, next: await nextTab.isVisible(), prev: await prevTab.isVisible() }),
      contentType: 'application/json',
    })
    expect.soft(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)

    // 通信失敗注入。権限と予定取得は実APIのまま、委任一覧だけを中断する。
    let interrupted = 0
    const delegationUrl = (url: URL) => url.pathname === `/api/v1/schedules/${id}/delegations`
    await page.route(delegationUrl, async route => { interrupted++; await route.abort() })
    await page.reload({ waitUntil: 'domcontentloaded' })
    const retry = page.getByTestId('schedule-delegation-error-retry').filter({ visible: true })
    await expect(retry).toBeVisible({ timeout: 15_000 })
    await readyForInteraction(page, retry)
    await measure('delegation-error-retry', retry)
    // チーム画面はCSSで切り替えるmobile/desktop各1パネルをmountする。
    // useApiは401のみ再試行するため、通信中断では各instanceの1回ずつとなる。
    try {
      await expect.poll(() => interrupted).toBe(2)
    } finally {
      await testInfo.attach(`delegation-network-fault-${width}.json`, {
        body: JSON.stringify({ interrupted, path: `/api/v1/schedules/${id}/delegations`, injected: 'route.abort', normalApiSuccessEvidence: false }),
        contentType: 'application/json',
      })
    }
    await page.unroute(delegationUrl)

    await page.goto('/teams/surroundings-missing-261017/schedule', { waitUntil: 'domcontentloaded' })
    await measure('shell-retry', page.getByRole('button').filter({ has: page.locator('.pi-refresh') }).first())

    expect.soft(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  })
}
