import { test, expect, type Page, type Locator } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * CMP-261030-0651: 緊急休業画面の送信履歴・プレビュー表の 390px 崩れ（DOM 寸法 E2E）。
 *
 * 実画面の実ルート /teams/{slug}/reservations（永続シェル ScopePageShell > TeamReservationsPanel >
 * Tabs/TabPanels > EmergencyClosureForm）をそのまま開いて測る。BE は不要で、API は page.route で
 * モックする（認証は localStorage の currentUser で通し、アクセストークンは /auth/refresh のモックが返す）。
 * 日時整形・ステータス表示・親の余白は本番コードのまま（検証専用ページは使わない）。
 *
 * AC-13/14 は CMP-261104-0336 でカード表示（md 未満）へ置換した。
 * AC-16: 1440x900 で全列・文字列・ボタンが欠けず「見えている」
 * （AC-15 の件数タグ i18n は単体 spec で検証する）
 */

test.use({ storageState: { cookies: [], origins: [] } })

const SLUG = 'ec-layout-clinic'
const REASON_200 = '院内設備の緊急点検および感染対策のため、'.repeat(11).slice(0, 200)

const CLOSURES = [
  {
    id: 1, teamId: 1, startDate: '2026-11-10', endDate: '2026-11-12', startTime: '09:00', endTime: '12:00',
    reason: REASON_200, subject: '臨時休業のお知らせ', messageBody: '本文', sentCount: 12,
    cancelReservations: false, createdBy: 1, createdAt: '2026-11-03T09:00:00',
  },
  {
    id: 2, teamId: 1, startDate: '2026-12-01', endDate: '2026-12-01', startTime: null, endTime: null,
    reason: '設備点検', subject: '臨時休業のお知らせ', messageBody: '本文', sentCount: 3,
    cancelReservations: false, createdBy: 1, createdAt: '2026-11-03T10:00:00',
  },
]
const CONFIRMATIONS = [
  { userId: 1, userFullName: '山田 太郎', userEmail: 'u1@example.com', appointmentAt: '2026-11-10T09:30:00', confirmed: true, confirmedAt: '2026-11-03T10:00:00', reminderSent: true },
  { userId: 2, userFullName: '鈴木 花子', userEmail: 'u2@example.com', appointmentAt: '2026-11-11T10:00:00', confirmed: false, confirmedAt: null, reminderSent: false },
]
const NOSPACE_A = 'A'.repeat(120)
const NOSPACE_JA = 'あ'.repeat(80)
const LONG_CLOSURES = [{ ...CLOSURES[0]!, reason: NOSPACE_A }, { ...CLOSURES[1]!, reason: NOSPACE_JA }]
const LONG_CONFIRMATIONS = [
  { ...CONFIRMATIONS[0]!, userFullName: NOSPACE_JA },
  { ...CONFIRMATIONS[1]!, userFullName: NOSPACE_A },
]
const PREVIEW = {
  startDate: '2026-11-10', endDate: '2026-11-12', startTime: null, endTime: null, affectedCount: 2,
  affectedReservations: [
    { reservationId: 1, userId: 1, userFullName: '山田 太郎', userEmail: 'u1@example.com', slotDate: '2026-11-10', startTime: '09:30:00', endTime: '10:00:00', status: 'CONFIRMED' },
    { reservationId: 2, userId: 2, userFullName: '鈴木 花子', userEmail: 'u2@example.com', slotDate: '2026-11-11', startTime: '10:00:00', endTime: '10:30:00', status: 'PENDING' },
  ],
}
const TEAM_NAME = 'レイアウト検証総合メディカルクリニック本院予約センター'
const TEAM = {
  id: 1, slug: SLUG, timezone: 'Asia/Tokyo',
  basicInfo: { name: TEAM_NAME }, location: { template: 'CLINIC' },
  visibility: { visibility: 'PUBLIC', supporterEnabled: false }, metadata: { version: 1, memberCount: 3 },
}

/** 認証（localStorage の currentUser）と API モックを仕込む。未モックの API は空配列で返し、一覧を返す。 */
async function setupMocks(page: Page, long = false): Promise<string[]> {
  const unmatched: string[] = []
  await page.addInitScript(() => {
    localStorage.setItem('currentUser', JSON.stringify({
      id: 1, email: 'admin@example.com', fullName: '管理 者', profileImageUrl: null, timezone: 'Asia/Tokyo', locale: 'ja',
    }))
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600_000))
  })
  const ok = (data: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ data }) })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const p = url.pathname.replace(/^\/api\/v1/, '')
    const method = route.request().method()
    if (method === 'OPTIONS') return route.fulfill({ status: 204 })
    if (p === '/auth/refresh') return route.fulfill(ok({ accessToken: 'a.' + btoa(JSON.stringify({ exp: 4102444800 })) + '.c', refreshToken: 'r', expiresIn: 3600 }))
    if (p === `/teams/${SLUG}/me/permissions`) return route.fulfill(ok({ roleName: 'ADMIN', permissions: [], scopeAdmin: true, canGrantAdminOnlyPermissions: true }))
    if (p === `/teams/${SLUG}`) return route.fulfill(ok(TEAM))
    if (p === `/teams/${SLUG}/modules`) return route.fulfill(ok([{ moduleSlug: 'reservation', isEnabled: true }]))
    if (p === '/me/teams') return route.fulfill(ok([{ id: 1, slug: SLUG }]))
    if (p === `/teams/${SLUG}/emergency-closures`) return route.fulfill(ok(long ? LONG_CLOSURES : CLOSURES))
    if (p === `/teams/${SLUG}/emergency-closures/preview`) return route.fulfill(ok(PREVIEW))
    if (p === `/teams/${SLUG}/emergency-closures/1/confirmations`) return route.fulfill(ok(long ? LONG_CONFIRMATIONS : CONFIRMATIONS))
    unmatched.push(`${method} ${p}`)
    return route.fulfill(ok([]))
  })
  return unmatched
}

/** 実画面の「緊急休業」タブまで進む。 */
async function openClosureTab(page: Page, long = false): Promise<void> {
  await setupMocks(page, long)
  await page.goto(`/teams/${SLUG}/reservations`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.getByRole('tab', { name: '緊急休業' }).click()
  await expect(page.locator('[data-testid="closure-history-reload"]')).toBeVisible({ timeout: 30_000 })
}

function historySection(page: Page): Locator {
  return page.locator('section', { has: page.locator('[data-testid="closure-history-reload"]') })
}

function previewSection(page: Page): Locator {
  return page.locator('section', { has: page.getByRole('button', { name: '影響する予約を確認' }) })
}

async function runPreview(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: '影響する予約を確認' }).click()
  const preview = previewSection(page)
  await expect(preview.locator('tbody tr')).toHaveCount(2)
  return preview
}

async function box(loc: Locator) {
  const b = await loc.boundingBox()
  if (!b) throw new Error('要素の寸法が取得できない（非表示・描画なし）')
  return b
}

/**
 * 要素が「本当に見えている」こと:
 * toBeVisible・寸法 >0・自身が切れていない（scrollWidth<=clientWidth）・
 * ビューポート幅の内側かつ最寄りの横スクロール容器（なければ表）の内側に収まる。
 * 横スクロール容器内でスクロールが必要な列は inScroll=true にして容器内判定を緩める（表の内側に収まるかのみ見る）。
 */
async function expectReallyVisible(el: Locator, label: string, viewportWidth: number, opts: { scrollableAllowed?: boolean } = {}) {
  await expect(el, `${label} が可視でない`).toBeVisible()
  const vis = await el.evaluate((n) => {
    const cs = getComputedStyle(n)
    let hiddenAncestor = false
    for (let a: Element | null = n; a; a = a.parentElement) {
      const s = getComputedStyle(a)
      if (s.visibility === 'hidden' || s.display === 'none' || s.opacity === '0') hiddenAncestor = true
    }
    return { visibility: cs.visibility, opacity: cs.opacity, hiddenAncestor }
  })
  expect(vis.hiddenAncestor, `${label} が visibility/display/opacity で隠れている`).toBe(false)
  const b = await box(el)
  expect(b.width, `${label} の幅が 0`).toBeGreaterThan(0)
  expect(b.height, `${label} の高さが 0`).toBeGreaterThan(0)
  const clip = await el.evaluate((n) => ({ sw: n.scrollWidth, cw: n.clientWidth }))
  expect(clip.sw, `${label} の中身が切れている`).toBeLessThanOrEqual(clip.cw)
  if (!opts.scrollableAllowed) {
    expect(b.x, `${label} がビューポート左外`).toBeGreaterThanOrEqual(0)
    expect(b.x + b.width, `${label} がビューポート右外 (${b.x + b.width})`).toBeLessThanOrEqual(viewportWidth)
  }
  // 最寄りの横スクロール容器（なければ祖先の table）の内側に収まる
  const inside = await el.evaluate((n) => {
    const r = n.getBoundingClientRect()
    let c: Element | null = n.closest('table')
    for (let a: Element | null = n.parentElement; a; a = a.parentElement) {
      const ox = getComputedStyle(a).overflowX
      if (ox === 'auto' || ox === 'scroll') { c = a; break }
    }
    if (!c) return true
    const cr = c.getBoundingClientRect()
    const table = n.closest('table')?.getBoundingClientRect()
    const scrollW = c.scrollWidth
    // スクロール容器の内容幅（scrollWidth）の範囲内、かつ縦方向は容器の内側
    return r.left >= cr.left - 1 && r.right <= cr.left + scrollW + 1 && (!table || (r.left >= table.left - 1 && r.right <= table.right + 1))
  })
  expect(inside, `${label} が表／スクロール容器の外にはみ出している`).toBe(true)
}

/** 見出しが折り返さず切れていない（1 行）。 */
async function expectHeaderIntact(th: Locator, label: string) {
  const m = await th.evaluate((el) => {
    const cs = getComputedStyle(el)
    return {
      height: el.getBoundingClientRect().height,
      lineHeight: parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.5,
      paddingY: parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom),
    }
  })
  expect(m.height - m.paddingY, `${label} が複数行に折り返している (${m.height})`).toBeLessThan(m.lineHeight * 1.6)
}

async function expectNoPageOverflow(page: Page, width: number) {
  const sw = await page.evaluate(() => document.documentElement.scrollWidth)
  expect(sw, `ページ全体が横にはみ出している (scrollWidth=${sw})`).toBeLessThanOrEqual(width)
}

test.describe('CMP-261104-0336 緊急休業 送信履歴・プレビュー カード表示 390px', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 })

  /** 要素が可視・寸法あり・ビューポート幅の内側に収まる（横スクロールしないと見えないものは不可）。 */
  async function expectInViewport(el: Locator, label: string, width: number) {
    await expect(el, `${label} が可視でない`).toBeVisible()
    const b = await box(el)
    expect(b.x, `${label} が左外`).toBeGreaterThanOrEqual(0)
    expect(b.x + b.width, `${label} が右外 (${b.x + b.width})`).toBeLessThanOrEqual(width)
    const clip = await el.evaluate((n) => ({ sw: n.scrollWidth, cw: n.clientWidth }))
    expect(clip.sw, `${label} の中身が切れている`).toBeLessThanOrEqual(clip.cw)
  }

  test('0336-1: 送信履歴カード — 期間・送信日時・理由・件数・ボタンが横スクロールなしで見える', async ({ page }) => {
    await openClosureTab(page)
    await expect(page.getByText(TEAM_NAME).first(), 'ヘッダーにチーム名が出ていない').toBeVisible()
    const history = historySection(page)
    await expect(history.locator('table'), '390px では表を出さない').toBeHidden()
    const cards = history.locator('[data-testid="closure-history-card"]')
    await expect(cards).toHaveCount(2)
    const card = cards.first()
    await expect(card.locator('[data-field="reason"]')).toHaveText(REASON_200)
    await expect(card.locator('[data-field="count"]')).toHaveText('12件')
    await expect(card.locator('[data-field="period"]')).toContainText('11月10日（火）〜11月12日（木）')
    await expect(card.locator('[data-field="period"]')).toContainText('09:00〜12:00')
    for (const f of ['period', 'sent-at', 'reason', 'count']) {
      await expectInViewport(card.locator(`[data-field="${f}"]`), `カード[${f}]`, 390)
    }
    const btn = card.getByRole('button', { name: /確認状況を見る/ })
    await expectInViewport(btn, '「確認状況を見る」', 390)
    const bb = await box(btn)
    expect(bb.width, `ボタン幅 ${bb.width}`).toBeGreaterThanOrEqual(44)
    expect(bb.height, `ボタン高さ ${bb.height}`).toBeGreaterThanOrEqual(44)
    await expectNoPageOverflow(page, 390)
  })

  test('0336-2: 確認状況展開 — 氏名・状態・予約日時・リマインドが画面内', async ({ page }) => {
    await openClosureTab(page)
    const history = historySection(page)
    await history.locator('[data-testid="closure-card-toggle"]').first().click()
    const items = history.locator('[data-testid="closure-card-confirmation"]')
    await expect(items).toHaveCount(2)
    for (let i = 0; i < 2; i++) {
      for (const f of ['name', 'appointment', 'status', 'reminder']) {
        await expectInViewport(items.nth(i).locator(`[data-field="${f}"]`), `確認[${i}][${f}]`, 390)
      }
    }
    await expect(items.nth(0).locator('[data-field="name"]')).toHaveText('山田 太郎')
    await expect(items.nth(0).locator('[data-field="status"]')).toContainText('確認済み')
    await expect(items.nth(0).locator('[data-field="reminder"]')).toContainText('送信済み')
    await expect(items.nth(1).locator('[data-field="status"]')).toContainText('未確認')
    await expectNoPageOverflow(page, 390)
  })

  test('0336-3: プレビュー — 氏名・日時・状態が切れず画面内', async ({ page }) => {
    await openClosureTab(page)
    await page.getByRole('button', { name: '影響する予約を確認' }).click()
    const cards = previewSection(page).locator('[data-testid="closure-preview-card"]')
    await expect(cards).toHaveCount(2)
    for (let i = 0; i < 2; i++) {
      await expectInViewport(cards.nth(i).locator('[data-field="name"]'), `プレビュー氏名[${i}]`, 390)
      await expectInViewport(cards.nth(i).locator('[data-field="datetime"]'), `プレビュー日時[${i}]`, 390)
    }
    await expect(cards.nth(0).locator('[data-field="datetime"]')).toContainText('11月10日（火） 09:30〜10:00')
    await expectNoPageOverflow(page, 390)
  })
})

test.describe('CMP-261104-0336 緊急休業 表/カード切替の境界 767/768px', () => {
  test.use({ viewport: { width: 767, height: 900 } })

  test('0336-6: 767px はカード、768px は表（横スクロールなし）', async ({ page }) => {
    await openClosureTab(page)
    const history = historySection(page)
    await expect(history.locator('[data-testid="closure-history-cards"]')).toBeVisible()
    await expect(history.locator('table')).toBeHidden()
    await expectNoPageOverflow(page, 767)
    await page.setViewportSize({ width: 768, height: 900 })
    await expect(history.locator('table').first()).toBeVisible()
    await expect(history.locator('[data-testid="closure-history-cards"]')).toBeHidden()
    await expectNoPageOverflow(page, 768)
  })
})

test.describe('CMP-261104-0336 長文の折り返し 390px', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  async function expectAllRightWithin(root: Locator, width: number, label: string) {
    const over = await root.evaluate((r, w) => {
      const bad: string[] = []
      r.querySelectorAll('*').forEach((n) => {
        const b = n.getBoundingClientRect()
        if (b.width > 0 && b.right > w + 0.5) bad.push(`${n.tagName}.${(n as HTMLElement).className}: ${b.right}`)
      })
      return bad
    }, width)
    expect(over, `${label}: 右端が ${width}px を超える要素`).toEqual([])
  }

  test('0336-6: 空白なしの長い理由・氏名でも履歴カード・患者カードが 390px に収まる', async ({ page }) => {
    await openClosureTab(page, true)
    const history = historySection(page)
    const cards = history.locator('[data-testid="closure-history-card"]')
    await expect(cards.first().locator('[data-field="reason"]')).toHaveText(NOSPACE_A)
    await expect(cards.nth(1).locator('[data-field="reason"]')).toHaveText(NOSPACE_JA)
    await expectAllRightWithin(cards.first(), 390, '履歴カード1')
    await expectAllRightWithin(cards.nth(1), 390, '履歴カード2')
    const btn = cards.first().locator('[data-testid="closure-card-toggle"]')
    await expect(btn).toBeVisible()
    await expect(btn).toBeEnabled()
    await btn.click()
    const items = history.locator('[data-testid="closure-card-confirmation"]')
    await expect(items).toHaveCount(2)
    await expect(items.nth(0).locator('[data-field="name"]')).toHaveText(NOSPACE_JA)
    await expect(items.nth(1).locator('[data-field="name"]')).toHaveText(NOSPACE_A)
    for (let i = 0; i < 2; i++) await expectAllRightWithin(items.nth(i), 390, `患者カード${i}`)
    await expectAllRightWithin(cards.first(), 390, '展開後の履歴カード1')
    await expect(btn).toBeVisible()
    await expect(btn).toBeEnabled()
    await expectNoPageOverflow(page, 390)
  })
})

test.describe('CMP-261104-0336 境界 767/768px — プレビュー・確認状況展開', () => {
  test.use({ viewport: { width: 767, height: 900 } })

  test('0336-6: プレビュー表示中・確認状況展開中に 767=カード / 768=表、幅内、状態保持', async ({ page }) => {
    await openClosureTab(page)
    const history = historySection(page)
    await history.locator('[data-testid="closure-card-toggle"]').first().click()
    await expect(history.locator('[data-testid="closure-card-confirmation"]')).toHaveCount(2)
    await page.getByRole('button', { name: '影響する予約を確認' }).click()
    const preview = previewSection(page)
    await expect(preview.locator('[data-testid="closure-preview-card"]')).toHaveCount(2)
    const hTable = history.locator('[data-testid="closure-history-table"]')
    const pTable = preview.locator('[data-testid="closure-preview-table"]')
    const hCards = history.locator('[data-testid="closure-history-card"]')
    const confCards = history.locator('[data-testid="closure-card-confirmation"]')
    const pCards = preview.locator('[data-testid="closure-preview-card"]')
    await expect(pTable).toBeHidden()
    await expect(hTable).toBeHidden()
    await expect(confCards.first()).toBeVisible()
    await expectNoPageOverflow(page, 767)

    await page.setViewportSize({ width: 768, height: 900 })
    await expect(hTable).toBeVisible()
    await expect(pTable).toBeVisible()
    await expect(pCards.first()).toBeHidden()
    await expect(confCards.first()).toBeHidden()
    // 状態保持: 展開中の内側表（2行）とプレビュー（2行）が残り、中身が見えている
    const innerRows = hTable.locator('table table tbody tr')
    await expect(innerRows).toHaveCount(2)
    await expect(innerRows.nth(0).locator('td').nth(0)).toBeVisible()
    await expect(innerRows.nth(0).locator('td').nth(0)).toHaveText('山田 太郎')
    await expect(innerRows.nth(1).locator('td').nth(0)).toHaveText('鈴木 花子')
    await expect(innerRows.nth(1).locator('td').nth(0)).toBeVisible()
    const pRows = pTable.locator('tbody tr')
    await expect(pRows).toHaveCount(2)
    await expect(pRows.nth(0).locator('td').nth(0)).toBeVisible()
    await expectNoPageOverflow(page, 768)

    await page.setViewportSize({ width: 767, height: 900 })
    await expect(hTable).toBeHidden()
    await expect(pTable).toBeHidden()
    await expect(hCards.first()).toBeVisible()
    await expect(confCards).toHaveCount(2)
    await expect(confCards.nth(0)).toBeVisible()
    await expect(confCards.nth(1)).toBeVisible()
    await expect(confCards.nth(0).locator('[data-field="name"]')).toHaveText('山田 太郎')
    await expect(pCards).toHaveCount(2)
    await expect(pCards.nth(0)).toBeVisible()
    await expect(pCards.nth(1)).toBeVisible()
    await expectNoPageOverflow(page, 767)
  })
})

test.describe('CMP-261030-0651 緊急休業 送信履歴・プレビュー表 1440px', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('AC-16: 全列・文字列・ボタンが欠けず「見えて」おり、横スクロールも出ない', async ({ page }) => {
    await openClosureTab(page)
    const history = historySection(page)

    // 履歴 5 列: 見出しと全行の各セルを列ごとに可視・寸法・クリップ・容器内で確かめる
    const labels = ['送信日時', '休業期間', '理由', '件数', '確認状況']
    const ths = history.locator('thead th')
    await expect(ths).toHaveCount(5)
    for (let i = 0; i < 5; i++) {
      await expect(ths.nth(i)).toHaveText(labels[i]!)
      await expectReallyVisible(ths.nth(i), `履歴見出し[${labels[i]}]`, 1440)
      await expectHeaderIntact(ths.nth(i), `履歴見出し[${labels[i]}]`)
    }
    const rows = history.locator('tbody > tr')
    await expect(rows).toHaveCount(2)
    for (let r = 0; r < 2; r++) {
      for (let c = 0; c < 5; c++) {
        await expectReallyVisible(rows.nth(r).locator('td').nth(c), `履歴セル[${r},${labels[c]}]`, 1440)
      }
    }

    const row = rows.first()
    await expect(row.locator('td').nth(2)).toHaveText(REASON_200)
    await expect(row.locator('td').nth(3)).toHaveText('12件')
    await expect(row.locator('td').nth(1)).toContainText('11月10日（火）〜11月12日（木）')
    await expect(row.locator('td').nth(1)).toContainText('09:00〜12:00')
    const btn = row.getByRole('button', { name: /確認状況を見る/ })
    await expectReallyVisible(btn, '「確認状況を見る」ボタン', 1440)
    const bb = await box(btn)
    expect(bb.width).toBeGreaterThanOrEqual(44)
    expect(bb.height).toBeGreaterThanOrEqual(44)
    expect((await box(row)).height, '1440px で理由行が高すぎる').toBeLessThanOrEqual(240)

    const scrollable = await history.locator('table').first().evaluate((el) => {
      const c = el.parentElement!
      return { sw: c.scrollWidth, cw: c.clientWidth }
    })
    expect(scrollable.sw, '1440px で履歴表が横スクロールしている').toBeLessThanOrEqual(scrollable.cw)

    // 展開: 内側 4 列（見出し・全セル・ステータス文言）
    await btn.click()
    const inner = history.locator('table table')
    await expect(inner.locator('thead th')).toHaveText(['患者名', '予約日時', '確認', 'リマインド'])
    for (let i = 0; i < 4; i++) await expectReallyVisible(inner.locator('thead th').nth(i), `内側見出し[${i}]`, 1440)
    const innerRows = inner.locator('tbody tr')
    await expect(innerRows).toHaveCount(2)
    for (let r = 0; r < 2; r++) {
      for (let c = 0; c < 4; c++) await expectReallyVisible(innerRows.nth(r).locator('td').nth(c), `内側セル[${r},${c}]`, 1440)
    }
    await expect(innerRows.nth(0).locator('td').nth(0)).toHaveText('山田 太郎')
    await expect(innerRows.nth(0).locator('td').nth(2)).toContainText('確認済み')
    await expect(innerRows.nth(0).locator('td').nth(3)).toContainText('送信済み')
    await expect(innerRows.nth(1).locator('td').nth(0)).toHaveText('鈴木 花子')
    await expect(innerRows.nth(1).locator('td').nth(2)).toContainText('未確認')

    // プレビュー 3 列
    const preview = await runPreview(page)
    const pths = preview.locator('thead th')
    await expect(pths).toHaveText(['患者名', '日時', 'ステータス'])
    for (let i = 0; i < 3; i++) await expectReallyVisible(pths.nth(i), `プレビュー見出し[${i}]`, 1440)
    const prow = preview.locator('tbody tr')
    for (let r = 0; r < 2; r++) {
      for (let c = 0; c < 3; c++) await expectReallyVisible(prow.nth(r).locator('td').nth(c), `プレビューセル[${r},${c}]`, 1440)
    }
    await expect(prow.nth(0).locator('td').nth(0)).toHaveText('山田 太郎')
    await expect(prow.nth(0).locator('td').nth(1)).toContainText('11月10日（火） 09:30〜10:00')

    await expectNoPageOverflow(page, 1440)
  })
})
