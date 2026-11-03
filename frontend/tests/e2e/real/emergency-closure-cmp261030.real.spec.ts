/**
 * CMP-261030-0649 / 0650 / 0651: 緊急休業画面の不具合3件の実機E2E（モックなし・実 BE・実 FE・実ブラウザ）。
 *
 * 0649 休業理由: テンプレ選択で自動入力・編集可 / カスタム・手入力でも送信成功 / 空・空白は確認ダイアログを開かず警告 /
 *               200字ちょうどは成功・201字目は打鍵でも貼り付けでも入らない / 確認ダイアログに理由が出る
 * 0650 患者名:  プレビュー表と送信履歴の確認状況パネルに患者のフルネームが出る
 * 0651 レイアウト: 390x844 と 1440x900 で送信履歴（折りたたみ／展開）・プレビュー表が崩れず、ページが横にはみ出さない。
 *               「確認状況を見る」は 44x44 以上。件数タグは ja で「N件」
 * ロール横断: ADMIN(正) / DEPUTY_ADMIN / MEMBER(負) / 部外者(負) / SYSTEM_ADMIN
 *
 * 対象の操作（入力・送信・タブ移動）はすべて画面で行う。API を使うのはログイン・前提データ作成・後始末だけ。
 * page.route によるモックは使わない。
 *
 * 実行例（検証用 BE=8083 / FE=3003。8081・3001 は別セッションが使うため触らない）:
 *   cd frontend && API_BASE_URL=http://localhost:8083 BASE_URL=http://localhost:3003 \
 *     npx playwright test -c playwright-emergency-closure-real.config.ts
 * 前提: 上記 BE の MANNSCHAFT_ALLOWED_ORIGINS に FE オリジンを入れて起動し、OPTIONS で 200 を実測しておく。
 */
import { expect, test, request as pwRequest, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { mkdirSync } from 'fs'
import {
  BE_API, FE_BASE, MEMBER, OUTSIDER, OWNER, SYSADMIN, apiLogin, apiMe, authHeaders, createThrowawayTeam,
  dbScalar, deleteNotificationsByIds, deleteTeam, gotoHydrated, maxNotificationId, notificationIdsSince,
  type Cred,
} from './helpers/g3457-notify'
import { loginViaApi } from '../fixtures/auth'

test.describe.configure({ mode: 'serial' })
test.setTimeout(420_000)

/** 副管理者（DEPUTY_ADMIN）として招待参加させるアカウント。 */
const DEPUTY: Cred = {
  // 注意: e2e-user は locale=en のため画面文言が英語になる。ja ロケールの e2e-dummy-6 を使う。
  email: process.env.EC_DEPUTY_EMAIL ?? 'e2e-dummy-6@test.mannschaft.local',
  password: process.env.EC_DEPUTY_PASSWORD ?? 'TestPass2026!',
  label: '副管理者',
}

const SHOT_DIR = process.env.EC_SHOT_DIR ?? 'test-results/emergency-closure-cmp261030'
mkdirSync(SHOT_DIR, { recursive: true })

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
function jstDay(offsetDays: number): { iso: string; dayCode: (typeof DAY_CODES)[number] } {
  const d = new Date(Date.now() + 9 * 3600_000 + offsetDays * 86400_000)
  const iso = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
  return { iso, dayCode: DAY_CODES[d.getUTCDay()]! }
}
const closureDay = jstDay(1)

/** BMP のみ（UTF-16 の長さ = 文字数）。maxlength の判定を曖昧にしない。 */
const REASON_200 = '院内設備の緊急点検および感染対策のため、'.repeat(11).slice(0, 200)
const SUBJECT_200 = '【重要】臨時休業のお知らせ（設備点検）'.repeat(11).slice(0, 200)
const OVER_250 = 'あいうえおかきくけこ'.repeat(25)

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let deputyCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let deputyToken = ''
let memberId = 0
let deputyId = 0
let memberFullName = ''
let team: { slug: string; id: number; name: string }
const baseline = { id: 0 }
const contexts: BrowserContext[] = []
const roleLog: string[] = []

async function openSession(browser: Browser, c: Cred, viewport = { width: 1440, height: 900 }): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL: FE_BASE, locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport,
    permissions: ['clipboard-read', 'clipboard-write'],
  })
  contexts.push(context)
  const page = await context.newPage()
  await loginViaApi(page, { email: c.email, password: c.password }, { apiBaseUrl: process.env.API_BASE_URL })
  return { context, page }
}

async function openClosureTab(page: Page): Promise<void> {
  await gotoHydrated(page, `/teams/${team.slug}/reservations`)
  const tab = page.getByRole('tab', { name: '緊急休業' })
  await expect(tab).toBeVisible({ timeout: 60_000 })
  // 新規チームの管理者に出る「メンバーの権限を初期設定」ダイアログは、dev サーバでは権限取得後かなり遅れて出る。
  // タブを押す前に通常操作（あとで決める）で閉じる。出ない場合（副管理者など）は 45 秒で諦める。
  const later = page.getByRole('button', { name: 'あとで決める' })
  if (await later.waitFor({ state: 'visible', timeout: 45_000 }).then(() => true, () => false)) {
    await later.click()
    await expect(later).toBeHidden({ timeout: 10_000 })
  }
  await tab.click()
  await expect(page.getByTestId('closure-reason')).toBeVisible({ timeout: 30_000 })
}

async function setPeriod(page: Page): Promise<void> {
  const dates = page.locator('input[type="date"]:visible')
  await dates.nth(0).fill(closureDay.iso)
  await dates.nth(1).fill(closureDay.iso)
}

async function fillBody(page: Page): Promise<void> {
  await page.getByTestId('closure-body').fill('実機E2E本文: 休業のお知らせです。')
}

async function pageOverflowPx(page: Page): Promise<number> {
  return page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth)
}

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

/** 最新の「休業を送信」を行う。確認ダイアログの表示→送信→トースト検証まで。 */
async function sendViaDialog(page: Page): Promise<string> {
  await page.getByRole('button', { name: '一括送信' }).first().click()
  const dlg = page.locator('.p-dialog')
  await expect(dlg).toBeVisible({ timeout: 10_000 })
  const dialogText = await dlg.innerText()
  const resp = page.waitForResponse(r => r.request().method() === 'POST' && /emergency-closures$/.test(new URL(r.url()).pathname))
  await dlg.getByRole('button', { name: '送信する' }).click()
  const r = await resp
  expect(r.status(), await r.text()).toBe(201)
  return dialogText
}

test.beforeAll(async () => {
  baseline.id = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  deputyCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  deputyToken = await apiLogin(deputyCtx, DEPUTY)
  const me = await memberCtx.get(`${BE_API}/users/me`, { headers: authHeaders(memberToken) })
  const md = (await me.json()).data as { id: number; lastName: string; firstName: string }
  memberId = md.id
  memberFullName = `${md.lastName} ${md.firstName}`
  deputyId = (await apiMe(deputyCtx, deputyToken)).id
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'ec0651')

  async function join(ctx: APIRequestContext, token: string, roleId: number): Promise<void> {
    const inv = await ownerCtx.post(`${BE_API}/teams/${team.slug}/invite-tokens`, { headers: authHeaders(ownerToken), data: { roleId, expiresIn: '7d', maxUses: 10 } })
    if (!inv.ok()) throw new Error(`招待作成失敗 role=${roleId}: ${inv.status()} ${await inv.text()}`)
    const t = ((await inv.json()).data as { token: string }).token
    const j = await ctx.post(`${BE_API}/invite/${t}/join`, { headers: authHeaders(token), data: {} })
    if (!j.ok()) throw new Error(`招待参加失敗 role=${roleId}: ${j.status()} ${await j.text()}`)
  }
  await join(memberCtx, memberToken, 4)
  // 副管理者ロールの招待は宛先指定（ROLE_009）で一般招待では参加できないため、MEMBER で参加させて管理者が昇格する（前提データ作成）
  await join(deputyCtx, deputyToken, 4)
  const promote = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/members/${deputyId}/role`, { headers: authHeaders(ownerToken), data: { roleId: 3 } })
  if (!promote.ok()) throw new Error(`副管理者への昇格失敗: ${promote.status()} ${await promote.text()}`)
  const perm = await deputyCtx.get(`${BE_API}/teams/${team.slug}/me/permissions`, { headers: authHeaders(deputyToken) })
  console.log(`[前提] 副管理者の roleName=${((await perm.json()).data as { roleName: string }).roleName}`)

  const cat = await ownerCtx.get(`${BE_API}/teams/${team.slug}/modules/catalog`, { headers: authHeaders(ownerToken) })
  const mod = ((await cat.json()).data.modules as Array<{ moduleId: number; slug: string }>).find(m => m.slug === 'reservation')!
  const tg = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/modules/${mod.moduleId}/toggle`, { headers: authHeaders(ownerToken), data: { moduleId: mod.moduleId, enabled: true } })
  expect(tg.ok(), await tg.text()).toBeTruthy()
  const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
  const h = await ownerCtx.put(`${BE_API}/teams/${team.slug}/reservation-settings/business-hours`, { headers: authHeaders(ownerToken), data: { hours } })
  expect(h.ok(), await h.text()).toBeTruthy()
  const line = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-lines`, { headers: authHeaders(ownerToken), data: { name: '面談室' } })
  expect(line.ok(), await line.text()).toBeTruthy()
  const lineId = (await line.json()).data.id as number
  const tpl = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-slot-templates`, {
    headers: authHeaders(ownerToken),
    data: { lineId, dayOfWeek: closureDay.dayCode, startTime: '10:00:00', endTime: '10:30:00', capacity: 3 },
  })
  expect(tpl.ok(), await tpl.text()).toBeTruthy()
  const sl = await ownerCtx.get(`${BE_API}/teams/${team.slug}/reservation-slots?from=${closureDay.iso}&to=${closureDay.iso}`, { headers: authHeaders(ownerToken) })
  const rows = (await sl.json()).data as Array<{ id: number; lineId: number | null; basic: { startTime: string } }>
  const slot = rows.find(r => r.lineId === lineId && r.basic?.startTime?.startsWith('10:00'))
  if (!slot) throw new Error(`枠が見つからない ${closureDay.iso}`)
  const rsv = await memberCtx.post(`${BE_API}/teams/${team.slug}/reservations`, { headers: authHeaders(memberToken), data: { reservationSlotId: slot.id, lineId, userNote: 'EC0651予約者' } })
  expect(rsv.ok(), await rsv.text()).toBeTruthy()
  console.log(`[前提] team=${team.slug}(id=${team.id}) member=${memberId}(${memberFullName}) deputy=${deputyId} 休業日=${closureDay.iso}`)
})

test.afterAll(async () => {
  for (const c of contexts) await c.close().catch(e => console.log('[後始末] context close 失敗', String(e)))
  try {
    if (!team) throw new Error('team 未作成のため DB 後始末は不要')
    // 作成元が確認できる（このチームのスコープ）通知・休業・予約データのみ、チーム ID 指定で削除する
    const ids = [
      ...notificationIdsSince(baseline.id, memberId, 'EMERGENCY_CLOSURE', { scopeId: team.id }),
      ...notificationIdsSince(baseline.id, deputyId, 'EMERGENCY_CLOSURE', { scopeId: team.id }),
    ]
    deleteNotificationsByIds(ids)
    dbScalar(`delete from emergency_closure_confirmations where emergency_closure_id in (select id from emergency_closures where team_id = ${team.id})`)
    dbScalar(`delete from emergency_closures where team_id = ${team.id}`)
    dbScalar(`delete from reservations where team_id = ${team.id}`)
    dbScalar(`delete from reservation_slots where team_id = ${team.id}`)
    dbScalar(`delete from reservation_slot_templates where team_id = ${team.id}`)
    console.log(`[後始末] team ${team.id} の休業・確認・予約・枠・テンプレを削除`)
  } catch (e) { console.log('[後始末] DB 削除失敗', String(e)) }
  // 長い実行でアクセストークン(15分)が切れるため、後始末の前に取り直す
  if (team) await deleteTeam(ownerCtx, await apiLogin(ownerCtx, OWNER), team.slug)
  console.log('--- ROLE LOG ---\n' + roleLog.join('\n'))
  await ownerCtx.dispose(); await memberCtx.dispose(); await deputyCtx.dispose()
})

test('0649-A ADMIN: テンプレ選択で理由が自動入力され編集でき、プレビューに患者フルネーム(0650)、確認ダイアログに理由、送信成功とトースト', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER)
  await openClosureTab(page)
  await setPeriod(page)
  await page.getByRole('button', { name: '設備メンテナンス' }).click()
  const reason = page.getByTestId('closure-reason')
  await expect(reason).toHaveValue('設備メンテナンス')
  await expect(page.getByTestId('closure-subject')).not.toHaveValue('')
  await reason.click()
  await page.keyboard.press('End')
  await page.keyboard.type('（編集）')
  await expect(reason).toHaveValue('設備メンテナンス（編集）')

  // 0650: プレビューで患者のフルネーム
  await page.getByRole('button', { name: '影響する予約を確認' }).click()
  await expect(page.getByText('1件の予約に通知します')).toBeVisible({ timeout: 30_000 })
  const previewRow = page.locator('table').filter({ hasText: '患者名' }).first().locator('tbody tr').first()
  await expect(previewRow.locator('td').first()).toHaveText(memberFullName)
  await expect(page.getByText('不明なユーザー')).toHaveCount(0)
  await shot(page, '0650-preview-admin-1440')

  const dialogText = await sendViaDialog(page)
  expect(dialogText).toContain('設備メンテナンス（編集）')
  await expect(page.getByText('1件に送信しました').first()).toBeVisible({ timeout: 30_000 })
  await shot(page, '0649-sent-admin-1440')
})

test('0649-B ADMIN: カスタムテンプレ・手入力でも送信成功し、送信件数のトーストが出る', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER)
  await openClosureTab(page)
  await setPeriod(page)
  await page.getByRole('button', { name: 'その他（手動入力）' }).click()
  await expect(page.getByTestId('closure-reason')).toHaveValue('')
  await page.getByTestId('closure-reason').pressSequentially('カスタム理由E2E')
  await page.getByTestId('closure-subject').pressSequentially('カスタム件名E2E')
  await fillBody(page)
  const dialogText = await sendViaDialog(page)
  expect(dialogText).toContain('カスタム理由E2E')
  await expect(page.getByText(/\d+件に送信しました/).first()).toBeVisible({ timeout: 30_000 })
})

test('0649-C ADMIN: 理由が空・空白のみなら確認ダイアログは開かず警告が出る', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER)
  await openClosureTab(page)
  await setPeriod(page)
  await page.getByRole('button', { name: 'その他（手動入力）' }).click()
  await page.getByTestId('closure-subject').pressSequentially('件名あり')
  await fillBody(page)
  for (const value of ['', '   ']) {
    const reason = page.getByTestId('closure-reason')
    await reason.fill(value)
    await page.getByRole('button', { name: '一括送信' }).first().click()
    await expect(page.getByText('休業理由を入力してください').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.p-dialog')).toHaveCount(0)
    await shot(page, `0649-empty-reason-${value === '' ? 'empty' : 'blank'}`)
    // 警告トーストが消えるのを待ってから次のケースへ
    await page.waitForTimeout(1_000)
  }
})

test('0649-D ADMIN: 理由・件名とも200字ちょうどは送信成功。201字目は打鍵でも貼り付けでも入らない', async ({ browser }) => {
  const { page } = await openSession(browser, OWNER)
  await openClosureTab(page)
  await setPeriod(page)
  await page.getByRole('button', { name: 'その他（手動入力）' }).click()
  const reason = page.getByTestId('closure-reason')
  const subject = page.getByTestId('closure-subject')

  // 打鍵: 201 字目を打っても 200 字で止まる
  for (const [loc, text200] of [[reason, REASON_200], [subject, SUBJECT_200]] as const) {
    await loc.click()
    await loc.pressSequentially(text200 + 'Z', { delay: 0 })
    const v = await loc.inputValue()
    expect(v.length).toBe(200)
    expect(v).toBe(text200)
  }
  // 貼り付け: 250 字をクリップボードから貼っても 200 字で切られる
  for (const loc of [reason, subject]) {
    await loc.fill('')
    await page.evaluate(t => navigator.clipboard.writeText(t), OVER_250)
    await loc.click()
    await page.keyboard.press('Control+V')
    const v = await loc.inputValue()
    expect(v.length).toBe(200)
    expect(v).toBe(OVER_250.slice(0, 200))
  }
  // 200 字ちょうどの理由・件名で送信（0651 の履歴データにもなる）
  await reason.fill(REASON_200)
  await subject.fill(SUBJECT_200)
  await expect(reason).toHaveValue(REASON_200)
  await fillBody(page)
  const dialogText = await sendViaDialog(page)
  expect(dialogText).toContain(REASON_200)
  await expect(page.getByText(/\d+件に送信しました/).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText('送信に失敗しました')).toHaveCount(0)
  await shot(page, '0649-200chars-sent-1440')
})

for (const vp of [{ name: '390x844', width: 390, height: 844 }, { name: '1440x900', width: 1440, height: 900 }]) {
  test(`0651/0650 ADMIN ${vp.name}: 送信履歴(折りたたみ/展開)・プレビュー表が崩れず、横にはみ出さず、ボタン44px以上、件数タグ「N件」、確認状況にフルネーム`, async ({ browser }) => {
    const { page } = await openSession(browser, OWNER, { width: vp.width, height: vp.height })
    await openClosureTab(page)
    // 履歴: 折りたたみ時
    const histSection = page.locator('section').filter({ has: page.getByRole('heading', { name: '送信履歴' }) })
    await expect(histSection.locator('tbody tr').first()).toBeVisible({ timeout: 30_000 })
    await expect(histSection.getByText(REASON_200).first()).toBeVisible()
    const countTag = histSection.locator('.p-tag').first()
    await expect(countTag).toHaveText(/^\d+件$/)
    expect(await pageOverflowPx(page), `${vp.name} 折りたたみ時にページが横にはみ出す`).toBeLessThanOrEqual(1)
    const toggleBtn = histSection.getByRole('button', { name: '確認状況を見る' }).first()
    await toggleBtn.scrollIntoViewIfNeeded()
    const box = (await toggleBtn.boundingBox())!
    console.log(`[${vp.name}] 確認状況を見る ${box.width}x${box.height} / 件数タグ="${await countTag.innerText()}"`)
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    // 理由セルが行からはみ出していない（列が潰れて 1 文字縦書き等にならない）
    const reasonCell = histSection.locator('tbody tr').first().locator('td').nth(2)
    const rc = (await reasonCell.boundingBox())!
    expect(rc.width, '理由セルが潰れている').toBeGreaterThanOrEqual(200)
    await shot(page, `0651-history-collapsed-${vp.name}`)

    // 履歴: 展開時（確認状況パネル。0650 のフルネーム）
    await toggleBtn.click()
    const panel = histSection.getByText('確認状況', { exact: true }).first()
    await expect(panel).toBeVisible({ timeout: 30_000 })
    const nameCell = histSection.locator('table table tbody tr').first().locator('td').first()
    await expect(nameCell).toHaveText(memberFullName, { timeout: 30_000 })
    expect(await pageOverflowPx(page), `${vp.name} 展開時にページが横にはみ出す`).toBeLessThanOrEqual(1)
    await shot(page, `0651-history-expanded-${vp.name}`)
    await toggleBtn.click() // 折りたたむ

    // プレビュー表
    await setPeriod(page)
    await page.getByRole('button', { name: '影響する予約を確認' }).click()
    await expect(page.getByText(/件の予約に通知します|対象期間に予約はありません/)).toBeVisible({ timeout: 30_000 })
    const prevSection = page.locator('section').filter({ has: page.getByRole('heading', { name: '影響する予約の確認' }) })
    const rows = prevSection.locator('tbody tr')
    if (await rows.count() > 0) {
      await expect(rows.first().locator('td').first()).toHaveText(memberFullName)
      const prevWrap = (await prevSection.locator('table').boundingBox())!
      expect(prevWrap.width).toBeGreaterThan(100)
    }
    expect(await pageOverflowPx(page), `${vp.name} プレビュー表でページが横にはみ出す`).toBeLessThanOrEqual(1)
    await shot(page, `0651-preview-${vp.name}`)
    // 確認ダイアログ（390 でも画面内に収まる）
    if (vp.width === 390) {
      await page.getByRole('button', { name: 'その他（手動入力）' }).click()
      await page.getByTestId('closure-reason').fill(REASON_200)
      await page.getByTestId('closure-subject').fill('件名')
      await fillBody(page)
      await page.getByRole('button', { name: '一括送信' }).first().click()
      const dlg = page.locator('.p-dialog')
      await expect(dlg).toBeVisible()
      const db = (await dlg.boundingBox())!
      console.log(`[390] 確認ダイアログ x=${db.x} w=${db.width}`)
      expect(db.x).toBeGreaterThanOrEqual(0)
      expect(db.x + db.width).toBeLessThanOrEqual(390 + 1)
      await shot(page, '0651-confirm-dialog-390x844')
    }
  })
}

/**
 * 負の視点を記録する。「読み込み前にタブが無いだけ」の偽合格を避けるため、必ず「期待する到達状態」を明示的に待ってから
 * タブ・送信ボタン 0 件を assert する。
 *  - arm: goto の前に応答の待ち受けを仕掛け、画面が確定した証拠を待つ関数を返す（API 応答の assert / 拒否文言 / 遷移先 URL）
 */
async function recordNegativeView(page: Page, label: string, url: string, arm: () => () => Promise<void>): Promise<void> {
  const settled = arm() // 応答の待ち受けは goto より前に仕掛ける
  await page.goto(url, { waitUntil: 'commit', timeout: 240_000 })
  await settled()
  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160)
  const hasTab = await page.getByRole('tab', { name: '緊急休業' }).count()
  const hasSend = await page.getByRole('button', { name: '一括送信' }).count()
  const line = `[${label}] ${url} -> ${new URL(page.url()).pathname} tab=${hasTab} send=${hasSend} body="${body}"`
  roleLog.push(line); console.log(line)
  await shot(page, `role-${label.replace(/[^A-Za-z0-9_-]/g, '_')}`)
  expect(hasTab).toBe(0)
  expect(hasSend).toBe(0)
}

const teamTopPath = () => `/teams/${team.slug}`
/** 管理画面の URL 直打ちがチームトップへ戻されたことを明示的に確認する */
const settledRedirectedToTeamTop = (page: Page) => async () => {
  await page.waitForURL(u => u.pathname === teamTopPath(), { timeout: 150_000 })
  await expect(page).toHaveURL(new RegExp(`${teamTopPath()}$`))
}
test('ロール DEPUTY_ADMIN: 緊急休業の導線があり、理由必須・プレビュー・送信ができる（BE/FE とも副管理者まで許可）', async ({ browser }) => {
  const { page } = await openSession(browser, DEPUTY)
  await openClosureTab(page)
  await setPeriod(page)
  await page.getByRole('button', { name: '天候・緊急事態' }).click()
  await expect(page.getByTestId('closure-reason')).toHaveValue('天候・緊急事態')
  await page.getByRole('button', { name: '影響する予約を確認' }).click()
  await expect(page.getByText(/件の予約に通知します/)).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('table').filter({ hasText: '患者名' }).first().locator('tbody tr').first().locator('td').first()).toHaveText(memberFullName)
  await sendViaDialog(page)
  await expect(page.getByText(/\d+件に送信しました/).first()).toBeVisible({ timeout: 30_000 })
  roleLog.push('[DEPUTY_ADMIN] tab あり・プレビュー(フルネーム)・送信 成功')
  await shot(page, 'role-deputy-sent')
})

const apiPath = (suffix: string) => `/api/v1/teams/${team.slug}${suffix}`
const waitApi = (page: Page, suffix: string) =>
  page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === apiPath(suffix), { timeout: 150_000 })

test('ロール MEMBER(負): 緊急休業のタブ・送信ボタンが出ない。URL 直打ち(管理画面)でも操作できない', async ({ browser }) => {
  const { page } = await openSession(browser, MEMBER)
  await recordNegativeView(page, 'MEMBER-reservations', `/teams/${team.slug}/reservations`, () => {
    // 親シェルと予約パネルは同じ GET /api/v1/teams/{slug}/me/permissions を呼ぶため、この応答だけではパネルの権限確定を保証できない。
    // パネル(TeamReservationsPanel.onMounted)は「自分の権限取得 → GET .../reservation-settings → 呼称取得」の順に呼ぶため、
    // パネル専用の reservation-settings 応答まで待てば、パネルの権限確定後であることが保証される。
    // 権限応答は「すべて」集める（親シェルとパネルの両方。片方だけ失敗しても見逃さない）
    const perms: Array<{ status: number; roleName: string | null }> = []
    const bodies: Promise<void>[] = []
    page.on('response', r => {
      if (r.request().method() === 'GET' && new URL(r.url()).pathname === apiPath('/me/permissions')) {
        bodies.push(r.json().then(
          j => { perms.push({ status: r.status(), roleName: (j as { data?: { roleName?: string } }).data?.roleName ?? null }) },
          () => { perms.push({ status: r.status(), roleName: null }) },
        ))
      }
    })
    const settings = waitApi(page, '/reservation-settings')
    return async () => {
      await settings
      await Promise.all(bodies)
      roleLog.push(`[MEMBER] me/permissions 応答=${JSON.stringify(perms)}`)
      expect(perms.length, '親シェルとパネルの権限応答が両方あること').toBeGreaterThanOrEqual(2)
      expect(perms.every(p => p.status === 200), `権限応答がすべて 200: ${JSON.stringify(perms)}`).toBe(true)
      expect(perms.every(p => p.roleName === 'MEMBER'), `roleName がすべて MEMBER: ${JSON.stringify(perms)}`).toBe(true)
      await expect(page.getByRole('tab', { name: '予約する' })).toBeVisible({ timeout: 150_000 })
      await page.waitForTimeout(2_000) // 描画の安定待ち
    }
  })
  await recordNegativeView(page, 'MEMBER-admin-reservations', `/teams/${team.slug}/admin/reservations`, () => settledRedirectedToTeamTop(page))
})

test('ロール OUTSIDER 部外者(負): 対象チームの緊急休業が見えず、URL 直打ちでも弾かれる', async ({ browser }) => {
  const { page } = await openSession(browser, OUTSIDER)
  await recordNegativeView(page, 'OUTSIDER-reservations', `/teams/${team.slug}/reservations`, () => {
    // チーム取得 API(GET /api/v1/teams/{slug})の拒否応答を待つ。拒否文言は SSR 初期状態でも出るため、文言だけでは到達を保証できない
    const teamResp = waitApi(page, '')
    return async () => {
      const status = (await teamResp).status()
      roleLog.push(`[OUTSIDER] GET ${apiPath('')} status=${status}`)
      expect(status).toBe(403)
      await expect(page.getByText('情報を取得できませんでした')).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('tab', { name: '予約する' })).toHaveCount(0)
      await page.waitForTimeout(2_000)
    }
  })
  await recordNegativeView(page, 'OUTSIDER-admin-reservations', `/teams/${team.slug}/admin/reservations`, () => settledRedirectedToTeamTop(page))
})
test('ロール SYSTEM_ADMIN: 緊急休業タブが出る（テナントの通常アカウントではないが、予約管理の権限判定は SYSTEM_ADMIN を許可する）', async ({ browser }) => {
  const { page } = await openSession(browser, SYSADMIN)
  await page.goto(`/teams/${team.slug}/reservations`, { waitUntil: 'commit', timeout: 240_000 })
  // 予約画面の本体（予約する タブ）が出るまで待つ。緊急休業タブの有無は記録のみ（仕様判断は殿）
  await expect(page.getByRole('tab', { name: '予約する' })).toBeVisible({ timeout: 150_000 })
  // 緊急休業タブは権限取得後に出る。SYSTEM_ADMIN には出ることを assert する（出なくなれば落ちる）
  await expect(page.getByRole('tab', { name: '緊急休業' })).toBeVisible({ timeout: 60_000 })
  const hasTab = await page.getByRole('tab', { name: '緊急休業' }).count()
  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160)
  roleLog.push(`[SYSTEM_ADMIN] /teams/${team.slug}/reservations -> ${new URL(page.url()).pathname} tab=${hasTab} body="${body}"`)
  await shot(page, 'role-sysadmin')
})
