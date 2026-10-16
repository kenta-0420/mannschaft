import { waitForHydratedShell } from '../helpers/waitForHydratedShell'
import { test, expect, type Locator, type Page } from '@playwright/test'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

async function prepare(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('currentUser', JSON.stringify({ id: 201, email: 'ui@example.com', fullName: '操作検証', timezone: 'Asia/Tokyo' }))
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000))
  })
  await page.route('**/api/v1/**', route => route.fulfill({ json: { data: [], meta: {} } }))
  await mockTeam(page)
  await mockTeamFeatureApis(page)
  await page.route('**/api/v1/admin/member-permissions?**', route => route.fulfill({ json: { data: { permissions: [] } } }))
}

for (const width of [360, 390, 312, 1024, 1280]) {
  test(`INSIGHT-I1: ${width}pxで招待PDFをホバーせず識別して保存できる`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await prepare(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens`, route => route.fulfill({ json: { data: [{ id: 99, token: 'ui-regression', roleName: 'MEMBER', expiresAt: null, maxUses: null, usedCount: 0, revokedAt: null, createdAt: '2026-01-01T00:00:00' }] } }))
    let releasePdf: (() => void) | undefined
    const pending = new Promise<void>(resolve => { releasePdf = resolve })
    await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens/99/pdf`, async route => {
      await pending
      await route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' })
    })
    await page.goto(`/teams/${TEAM_ID}/invites`, { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    const pdf = page.getByRole('button', { name: 'QRコードPDF印刷', exact: true })
    await expect(pdf).toBeVisible()
    await expect(pdf).toHaveText('QRコードPDF印刷')
    const box = (await pdf.boundingBox())!
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(await page.evaluate(() => document.documentElement.clientWidth))
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
    const download = page.waitForEvent('download')
    await pdf.click()
    await expect(pdf).toBeDisabled()
    releasePdf!()
    await download
    await expect(pdf).toBeEnabled()
  })

  test(`INSIGHT-I6-CHAT: ${width}pxでチャンネル追加が44pxあり作成画面を開ける`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await prepare(page)
    await page.route('**/api/v1/chat/channels?**', route => route.fulfill({ json: { data: [] } }))
    await page.goto('/chat', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    const add = page.getByRole('button', { name: 'チャンネル追加', exact: true }).first()
    await expect(add).toBeVisible()
    const box = (await add.boundingBox())!
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    await add.click()
    await expect(page.getByRole('dialog')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  })
}

test('INSIGHT-I1-ERROR: PDF取得失敗を通知し再操作できる', async ({ page }) => {
  await prepare(page)
  await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens`, route => route.fulfill({ json: { data: [{ id: 99, token: 'ui-regression', roleName: 'MEMBER', expiresAt: null, maxUses: null, usedCount: 0, revokedAt: null, createdAt: '2026-01-01T00:00:00' }] } }))
  await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens/99/pdf`, route => route.fulfill({ status: 500, json: { message: 'PDF生成失敗' } }))
  await page.goto(`/teams/${TEAM_ID}/invites`, { waitUntil: 'domcontentloaded' })
  await waitForHydratedShell(page)
  const pdf = page.getByRole('button', { name: 'QRコードPDF印刷', exact: true })
  await pdf.click()
  await expect(page.getByText('PDF印刷に失敗しました')).toBeVisible()
  await expect(pdf).toBeEnabled()
})

test('INSIGHT-I1-DE: 360pxの長い翻訳と200%文字でもPDF操作を読めてページが横に溢れない', async ({ page, baseURL }) => {
  await page.setViewportSize({ width: 360, height: 900 })
  await page.context().addCookies([{ name: 'i18n_locale', value: 'de', url: baseURL! }])
  await prepare(page)
  await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens`, route => route.fulfill({ json: { data: [{ id: 99, token: 'ui-regression', roleName: 'MEMBER', expiresAt: null, maxUses: null, usedCount: 0, revokedAt: null, createdAt: '2026-01-01T00:00:00' }] } }))
  await page.goto(`/teams/${TEAM_ID}/invites`, { waitUntil: 'domcontentloaded' })
  await waitForHydratedShell(page)
  const pdf = page.getByRole('button', { name: 'QR PDF drucken', exact: true })
  await expect(pdf).toBeVisible()
  await pdf.evaluate(element => { element.style.setProperty('font-size', `${parseFloat(getComputedStyle(element).fontSize) * 2}px`, 'important') })
  const box = (await pdf.boundingBox())!
  expect(box.width).toBeGreaterThanOrEqual(44)
  expect(box.height).toBeGreaterThanOrEqual(44)
  expect(await pdf.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
})

test('INSIGHT-CONTACT: 360pxで発行・コピー・無効化と発行フォームが44pxある', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 900 })
  await prepare(page)
  await page.route('**/api/v1/contact-invite-tokens', route => route.fulfill({ json: { data: [{ id: 77, token: 'contact-ui', label: '操作検証', inviteUrl: 'https://example.com/contact-invite/contact-ui', qrCodeUrl: 'https://example.com/contact-invite/contact-ui/qr', maxUses: 5, usedCount: 0, expiresAt: null, createdAt: '2026-01-01T00:00:00' }] } }))
  await page.goto('/settings/contact-invite-tokens', { waitUntil: 'domcontentloaded' })
  await waitForHydratedShell(page)
  const open = page.getByRole('button', { name: '新しいURLを発行', exact: true })
  await open.click()
  const label = page.getByPlaceholder('SNS用など')
  await expect(label).toBeVisible()
  const cancel = page.getByRole('button', { name: 'キャンセル', exact: true })
  // Selectのcomboboxは内側span。クリックを受ける外枠divを測る。
  const selects = (await page.getByRole('combobox').all()).map(combobox => combobox.locator('xpath=..'))
  for (const target of [open, page.locator('button:has(.pi-copy)'), page.locator('button:has(.pi-trash)'), label, ...selects, page.getByRole('button', { name: '発行', exact: true }), cancel]) {
    await expect(target).toBeVisible()
    const box = (await target.boundingBox())!
    expect.soft(box.width).toBeGreaterThanOrEqual(44)
    expect.soft(box.height).toBeGreaterThanOrEqual(44)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  await cancel.click()
  await expect(label).not.toBeVisible()
})

test('INSIGHT-LOCATION: 360pxの空拠点一覧から追加Dialogを開き44pxで操作できる', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 900 })
  await prepare(page)
  await page.route(`**/api/v1/teams/${TEAM_ID}/checkins/stats?**`, route => route.fulfill({ json: { data: { totalCheckins: 0, uniqueMembers: 0, byDayOfWeek: [], period: { from: '2026-10-01', to: '2026-10-01' } } } }))
  await page.goto(`/teams/${TEAM_ID}/member-cards`, { waitUntil: 'domcontentloaded' })
  await waitForHydratedShell(page)
  await page.getByRole('tab', { name: '拠点管理', exact: true }).click()
  await expect(page.getByText('拠点が登録されていません', { exact: true })).toBeVisible()
  const add = page.getByRole('button', { name: '拠点を追加', exact: true })
  await expect(add).toBeVisible()
  await add.click()
  const dialog = page.getByRole('dialog', { name: '拠点を追加', exact: true })
  await expect(dialog).toBeVisible()
  // 表示直後はenterのscaleアニメーション中なので、通常サイズに戻ってから操作領域を測る。
  await expect(dialog).not.toHaveClass(/p-dialog-enter-active/)
  const cancel = dialog.getByRole('button', { name: 'キャンセル', exact: true })
  for (const target of [add, cancel, dialog.getByRole('button', { name: '作成', exact: true })]) {
    const box = (await target.boundingBox())!
    expect.soft(box.width).toBeGreaterThanOrEqual(44)
    expect.soft(box.height).toBeGreaterThanOrEqual(44)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  await cancel.click()
  await expect(dialog).not.toBeVisible()
})

for (const width of [360, 390, 1024, 1280]) {
  test(`INSIGHT-INVITE-EN: ${width}pxで英語の条件を確認して招待を作成しPDFを取得できる`, async ({ page, baseURL }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.context().addCookies([{ name: 'i18n_locale', value: 'en', url: baseURL! }])
    await prepare(page)
    let created = false
    let payload: unknown
    await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens`, async route => {
      if (route.request().method() === 'POST') {
        payload = route.request().postDataJSON()
        created = true
        await route.fulfill({ status: 201, json: { data: {} } })
        return
      }
      await route.fulfill({ json: { data: created ? [{ id: 99, token: 'english-invite', roleName: 'MEMBER', expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(), maxUses: null, usedCount: 0, revokedAt: null, createdAt: '2026-10-16T00:00:00' }] : [] } })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens/99/pdf`, route => route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' }))
    await page.goto(`/teams/${TEAM_ID}/invites`, { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    await expect.soft(page.getByText('No invite links yet', { exact: true })).toBeVisible({ timeout: 1000 })
    const open = page.locator('button:has(.pi-plus)').last()
    await expect.soft(open).toHaveAccessibleName('New invite link', { timeout: 1000 })
    await open.click()
    const dialog = page.getByRole('dialog')
    await expect.soft(dialog).toHaveAccessibleName('Create invite link', { timeout: 1000 })
    await expect(dialog).toBeVisible()
    await expect(dialog).not.toHaveClass(/p-dialog-enter-active/)
    await expect.soft(dialog).not.toContainText(/[ぁ-んァ-ヶ一-龯]/, { timeout: 1000 })
    await expect.soft(dialog.getByText('Maximum uses (leave blank for unlimited)', { exact: true })).toBeVisible({ timeout: 1000 })
    const combos = dialog.getByRole('combobox')
    await expect.soft(combos.nth(0)).toHaveText('Member', { timeout: 1000 })
    await expect.soft(combos.nth(1)).toHaveText('7 days', { timeout: 1000 })
    // Selectのクリックを受けるroot divを測定する。内部combobox spanの寸法では判定しない。
    const selectRoots = [combos.nth(0).locator('xpath=..'), combos.nth(1).locator('xpath=..')]
    for (const target of [open, ...selectRoots, dialog.getByRole('spinbutton'), dialog.locator('.p-dialog-close-button'), dialog.locator('.p-dialog-footer button').first(), dialog.locator('button:has(.pi-check)')]) {
      const box = await target.boundingBox()
      expect(box).not.toBeNull()
      expect.soft(box!.width).toBeGreaterThanOrEqual(44)
      expect.soft(box!.height).toBeGreaterThanOrEqual(44)
    }
    await dialog.locator('button:has(.pi-check)').click()
    await expect(dialog).not.toBeVisible()
    expect(payload).toEqual({ roleId: 4, expiresIn: '7d', maxUses: null })
    const pdf = page.getByRole('button', { name: 'Print QR PDF', exact: true })
    await expect(pdf).toBeVisible()
    await expect.soft(page.getByRole('button', { name: 'Copy invite URL', exact: true })).toBeVisible({ timeout: 1000 })
    await expect.soft(page.getByRole('button', { name: 'Revoke invite link', exact: true })).toBeVisible({ timeout: 1000 })
    const download = page.waitForEvent('download')
    await pdf.click()
    await download
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
  })
}

for (const language of [
  { locale: 'ja', create: '新しいURLを発行', close: '閉じる', cancel: 'キャンセル', placeholder: 'SNS用など' },
  { locale: 'en', create: 'Issue New URL', close: 'Close', cancel: 'Cancel', placeholder: 'e.g. For SNS' },
]) {
  test(`INSIGHT-CONTACT-HEADER: ${language.locale}の見出しと操作ラベルを狭幅・文字拡大でも読める`, async ({ page, baseURL }, testInfo) => {
    await page.context().addCookies([{ name: 'i18n_locale', value: language.locale, url: baseURL! }])
    await prepare(page)
    await page.goto('/settings/contact-invite-tokens', { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    const title = page.getByRole('heading', { level: 1 })
    const help = page.getByTestId('page-header-help')
    const create = page.getByRole('button', { name: language.create, exact: true })
    await expect(create).toBeVisible()

    async function check(width: number, enlarged: boolean) {
      await page.setViewportSize({ width, height: 900 })
      await page.evaluate(async () => {
        await document.fonts.ready
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      })
      const titleBox = (await title.boundingBox())!
      const row = (await title.locator('..').boundingBox())!
      const rowStyle = await title.locator('..').evaluate(element => ({ flexWrap: getComputedStyle(element).flexWrap, display: getComputedStyle(element).display }))
      if (width < 640) expect.soft(titleBox.width).toBeGreaterThanOrEqual(row.width - 1)
      const controls = []
      for (const button of [help, create]) {
        const box = (await button.boundingBox())!
        const label = await button.locator('.p-button-label').evaluate(element => {
          const range = document.createRange()
          range.selectNodeContents(element)
          return {
            text: element.textContent,
            scrollWidth: element.scrollWidth,
            clientWidth: element.clientWidth,
            rects: [...range.getClientRects()].map(rect => ({ left: rect.left, right: rect.right, top: rect.top })),
          }
        })
        expect.soft(box.width).toBeGreaterThanOrEqual(44)
        expect.soft(box.height).toBeGreaterThanOrEqual(44)
        expect.soft(label.rects.length).toBeLessThanOrEqual(enlarged ? 2 : 1)
        expect.soft(label.scrollWidth).toBeLessThanOrEqual(label.clientWidth)
        for (const rect of label.rects) {
          expect.soft(rect.left).toBeGreaterThanOrEqual(box.x - 0.5)
          expect.soft(rect.right).toBeLessThanOrEqual(box.x + box.width + 0.5)
        }
        if (width < 640) expect.soft(box.y).toBeGreaterThanOrEqual(titleBox.y + titleBox.height)
        else expect.soft(box.y).toBeLessThan(titleBox.y + titleBox.height)
        controls.push({ box, label })
      }
      const documentSize = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
      expect.soft(documentSize.scroll).toBeLessThanOrEqual(documentSize.client)
      await testInfo.attach(`header-${language.locale}-${width}-${enlarged ? '200' : '100'}`, { body: JSON.stringify({ titleBox, row, rowStyle, controls, documentSize }, null, 2), contentType: 'application/json' })
      await testInfo.attach(`header-${language.locale}-${width}-${enlarged ? '200' : '100'}-image`, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
    }

    for (const width of [360, 390, 1280]) await check(width, false)
    for (const target of [title, help, create]) {
      await target.evaluate(element => {
        const style = getComputedStyle(element)
        const fontSize = Number.parseFloat(style.fontSize)
        const lineHeight = Number.parseFloat(style.lineHeight)
        element.style.fontSize = `${fontSize * 2}px`
        element.style.lineHeight = `${lineHeight * 2}px`
      })
    }
    for (const width of [360, 390]) await check(width, true)
    await help.click()
    const guide = page.getByTestId('contact-invite-guide-modal')
    await expect(guide).toBeVisible()
    await guide.getByRole('button', { name: language.close, exact: true }).last().click()
    await expect(guide).not.toBeVisible()
    await create.click()
    const input = page.getByPlaceholder(language.placeholder)
    await expect(input).toBeVisible()
    await page.getByRole('button', { name: language.cancel, exact: true }).click()
    await expect(input).not.toBeVisible()
  })
}

/** ナビゲータを通常クリックし、smooth scroll の停止後にリンクを操作する。 */
async function revealScopeTab(tabRoot: Locator, link: Locator) {
  for (let attempt = 0; attempt <= 6; attempt++) {
    const state = await link.evaluate(element => {
      const root = element.closest('[data-pc-name="tablist"]')!
      const content = root.querySelector('[data-pc-section="content"]')!
      const rect = element.getBoundingClientRect(), viewport = content.getBoundingClientRect()
      let left = Math.max(viewport.left, 0), right = Math.min(viewport.right, innerWidth)
      for (const button of root.querySelectorAll('button[data-pc-group-section="navigator"]')) {
        const box = button.getBoundingClientRect()
        if (button.getAttribute('data-pc-section') === 'prevbutton') left = Math.max(left, box.right)
        if (button.getAttribute('data-pc-section') === 'nextbutton') right = Math.min(right, box.left)
      }
      const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2
      const hit = document.elementFromPoint(x, y)
      return { ready: element.contains(hit) && x > left && x < right, direction: x <= left ? 'prevbutton' : x >= right ? 'nextbutton' : null, scrollLeft: content.scrollLeft, x: rect.x }
    })
    if (state.ready) return
    expect(attempt, '通常ナビゲータ6回以内でタブを操作できる').toBeLessThan(6)
    expect(state.direction, 'ナビゲータで隠れた側へ移動する').not.toBeNull()
    await tabRoot.locator(`button[data-pc-section="${state.direction}"]`).click()
    const settled = await link.evaluate((element, before) => new Promise<boolean>(resolve => {
      const root = element.closest('[data-pc-name="tablist"]')!
      const content = root.querySelector('[data-pc-section="content"]')!
      const rect = (node: Element) => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height] }
      let started = false, stableSince = 0, previous = '', frame = 0, timer = 0
      const finish = (ok: boolean) => { cancelAnimationFrame(frame); clearTimeout(timer); resolve(ok) }
      const sample = () => {
        const now = performance.now(), target = rect(element)
        const signature = JSON.stringify([content.scrollLeft, target, rect(content), [...root.querySelectorAll<HTMLButtonElement>('button[data-pc-group-section="navigator"]')].filter(button => button.getClientRects().length).map(button => [button.getAttribute('data-pc-section'), button.disabled, rect(button)])])
        started ||= Math.abs(content.scrollLeft - before.scrollLeft) > 0.5 || Math.abs(target[0]! - before.x) > 0.5
        if (started) {
          if (signature !== previous) { previous = signature; stableSince = now }
          if (now - stableSince >= 200) { finish(true); return }
        }
        frame = requestAnimationFrame(sample)
      }
      timer = window.setTimeout(() => finish(false), 10000)
      frame = requestAnimationFrame(sample)
    }), state)
    expect(settled, '移動開始後に矩形・scroll位置・ナビゲータ構成が200ms連続安定する').toBe(true)
  }
}

// ADMIN は sidebar/lens が同居するため、MEMBER の横幅確認だけではタブの圧縮を検出できない。
for (const width of [360, 390, 768, 1280]) {
  test(`INSIGHT-SHELL-NAV: ${width}pxで切替操作がタブを潰さず自然に招待へ移動できる`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await prepare(page)
    await page.goto(`/teams/${TEAM_ID}`, { waitUntil: 'domcontentloaded' })
    await waitForHydratedShell(page)
    const invite = page.locator(`[role="tablist"] a[href="/teams/${TEAM_ID}/invites"]`)
    await expect(invite).toBeVisible()
    const tabRoot = page.locator('[data-pc-name="tablist"]').filter({ has: invite })
    const menu = page.getByTestId('scope-sidebar-toggle')
    const lens = page.getByTestId('scope-lens-admin').locator('xpath=..')
    await expect(menu).toBeVisible()
    await expect(lens).toBeVisible()
    const content = tabRoot.locator('[data-pc-section="content"]')
    const contentBox = (await content.boundingBox())!
    const tabBox = (await tabRoot.boundingBox())!
    const menuBox = (await menu.boundingBox())!
    const lensBox = (await lens.boundingBox())!
    const navigatorWidths = await tabRoot.locator('button[data-pc-group-section="navigator"]').evaluateAll(buttons =>
      buttons.reduce((sum, button) => sum + button.getBoundingClientRect().width, 0),
    )
    // 両ナビゲータが覆う幅を差し引いても、リンクを操作できる正の領域が残る。
    expect(contentBox.width - navigatorWidths).toBeGreaterThanOrEqual(44)
    if (width < 768) {
      expect(menuBox.y).toBeGreaterThanOrEqual(tabBox.y + tabBox.height - 1)
      expect(lensBox.y).toBeGreaterThanOrEqual(tabBox.y + tabBox.height - 1)
    } else {
      // md 以上は従来どおり同じ行に配置する。
      expect(menuBox.y).toBeLessThan(tabBox.y + tabBox.height)
      expect(lensBox.y).toBeLessThan(tabBox.y + tabBox.height)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0)
    // URL 直打ちで招待に着地せず、実際に表示されたタブを通常クリックする。
    await revealScopeTab(tabRoot, invite)
    await invite.click()
    await expect(page).toHaveURL(new RegExp(`/teams/${TEAM_ID}/invites$`))
    await expect(page.getByRole('button', { name: '新規作成', exact: true })).toBeVisible()
  })
}
