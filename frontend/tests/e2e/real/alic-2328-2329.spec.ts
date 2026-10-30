/**
 * CMP-261028-2328（予約者向け通知の遷移先 slug 化・#4226）と、緊急休業の一括送信の件数表示（#4221）の実機E2E。
 *
 * 実 BE・実 FE・実ブラウザで踏む。page.route による API の横取りはしない。
 * API を使うのは次の3用途だけ:
 *   - ログイン（loginViaApi）
 *   - 前提データの作成（チーム・参加・モジュール有効化・営業時間・ライン・枠・予約・確定・リマインド行）
 *   - 後始末（チーム削除）。通知は DB を ID 指定で削除する（このテストが作ったチーム scope の行だけ）
 * 認可の負の視点で API 直叩きを1回使う（SYSTEM_ADMIN の 403 確認）が、これは「画面に導線が無いことの裏取り」であり、
 * 画面側の判定の代替ではない。
 *
 * E2E-2328-1  MEMBER が通知一覧から予約系通知（強制キャンセル・キャンセル待ち空き・予約リマインド）をクリック
 *             → URL が /teams/{slug}/reservations（数値でない）になり、予約画面が表示され、「チームが見つかりません」が出ない
 * E2E-2328-2  部外者が /teams/{slug}/reservations を URL 直打ち → 拒否される
 * E2E-2329-1  ADMIN が緊急休業の画面から実際に一括送信 → トーストと送信履歴に実際の宛先数（N件）が出る
 * E2E-2329-2  MEMBER・部外者が緊急休業の導線を持たない（URL 直打ちを含む）
 * E2E-SYS     SYSTEM_ADMIN の観測。緊急休業 API は AccessGuard が SYSTEM_ADMIN を常に通す設計（送信はせず GET で確認）
 *
 * 実行例（検証 BE=8081・検証 FE=3001 を起動済みであること）:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *   node node_modules/playwright/cli.js test <設定> tests/e2e/real/alic-2328-2329.spec.ts
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { fillInput, selectDropdown } from '../helpers/form'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, SYSADMIN, apiLogin, apiMe, authHeaders, createThrowawayTeam, dbScalar,
  deleteTeam, gotoHydrated, joinAsMember, maxNotificationId, notificationIdsSince, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })
test.setTimeout(480_000)

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
const WEEKDAY_JA = ['日', '月', '火', '水', '木', '金', '土'] as const
function jstDay(offsetDays: number): { iso: string; dayCode: (typeof DAY_CODES)[number]; weekdayJa: string; rowLabel: string } {
  const d = new Date(Date.now() + 9 * 3600_000 + offsetDays * 86400_000)
  const iso = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
  return {
    iso, dayCode: DAY_CODES[d.getUTCDay()]!, weekdayJa: WEEKDAY_JA[d.getUTCDay()]!,
    rowLabel: `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[d.getUTCDay()]})`,
  }
}

// 曜日が互いに重ならない4日（定期予約不可枠は曜日単位で効くため、強制キャンセルの日だけ別曜日にする）
const closureDay = jstDay(1)
const waitDay = jstDay(2)
const remindDay = jstDay(3)
const forceDay = jstDay(4)

/** 拒否画面に実際に出た文言（2026-10-30 実測: 「このコンテンツを閲覧する権限がありません」）。 */
const DENIED_TEXT = /閲覧する権限がありません|権限がありません/

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let memberId = 0
let ownerId = 0
let team: { slug: string; id: number; name: string }
let lineId = 0
const sessions: BrowserContext[] = []
const baseline = { id: 0 }
const stamp = Date.now()
const closureSubject = `A2328休業_${stamp}`
const forceReason = `A2328強行_${stamp}`
let remindReservationId = 0
let waitSlot = 0

async function createTemplateAndFindSlot(day: ReturnType<typeof jstDay>, start: string, capacity: number): Promise<number> {
  const tpl = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-slot-templates`, {
    headers: authHeaders(ownerToken),
    data: { lineId, dayOfWeek: day.dayCode, startTime: `${start}:00`, endTime: `${start.slice(0, 2)}:30:00`, capacity },
  })
  if (!tpl.ok()) throw new Error(`テンプレ作成失敗: ${tpl.status()} ${await tpl.text()}`)
  const res = await ownerCtx.get(`${BE_API}/teams/${team.slug}/reservation-slots?from=${day.iso}&to=${day.iso}`, { headers: authHeaders(ownerToken) })
  const rows = (await res.json()).data as Array<{ id: number; lineId: number | null; basic: { startTime: string } }>
  const found = rows.find(r => r.lineId === lineId && r.basic?.startTime?.startsWith(start))
  if (!found) throw new Error(`枠が見つからない ${day.iso} ${start}`)
  return found.id
}

async function reserve(ctx: APIRequestContext, token: string, slotId: number, note: string): Promise<number> {
  const r = await ctx.post(`${BE_API}/teams/${team.slug}/reservations`, {
    headers: authHeaders(token), data: { reservationSlotId: slotId, lineId, userNote: note },
  })
  if (!r.ok()) throw new Error(`予約失敗: ${r.status()} ${await r.text()}`)
  return (await r.json()).data.id
}

async function confirm(reservationId: number): Promise<void> {
  const r = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservations/${reservationId}/confirm`, { headers: authHeaders(ownerToken), data: {} })
  console.log(`[前提] reservation ${reservationId} confirm status=${r.status()}`)
}

test.beforeAll(async () => {
  baseline.id = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  memberId = (await apiMe(memberCtx, memberToken)).id
  ownerId = (await apiMe(ownerCtx, ownerToken)).id
  console.log(`[前提] ownerId=${ownerId} memberId=${memberId} baselineNotificationId=${baseline.id}`)
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'a2328')
  console.log(`[前提] 使い捨てチーム slug=${team.slug} 数値id=${team.id}`)
  expect(await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)).toBe('MEMBER')

  const cat = await ownerCtx.get(`${BE_API}/teams/${team.slug}/modules/catalog`, { headers: authHeaders(ownerToken) })
  const mod = ((await cat.json()).data.modules as Array<{ moduleId: number; slug: string }>).find(m => m.slug === 'reservation')!
  const tg = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/modules/${mod.moduleId}/toggle`, { headers: authHeaders(ownerToken), data: { moduleId: mod.moduleId, enabled: true } })
  expect(tg.ok(), await tg.text()).toBeTruthy()
  const hours = DAY_CODES.map(code => ({ dayOfWeek: code, isOpen: true, openTime: '08:00:00', closeTime: '22:00:00' }))
  const h = await ownerCtx.put(`${BE_API}/teams/${team.slug}/reservation-settings/business-hours`, { headers: authHeaders(ownerToken), data: { hours } })
  expect(h.ok(), await h.text()).toBeTruthy()
  const line = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservation-lines`, { headers: authHeaders(ownerToken), data: { name: '面談室' } })
  expect(line.ok(), await line.text()).toBeTruthy()
  lineId = (await line.json()).data.id

  // 緊急休業の宛先: 会員と管理者の2人が予約する（宛先数は distinct ユーザー数 = 2 になるはず）
  const closureSlot = await createTemplateAndFindSlot(closureDay, '10:00', 2)
  await reserve(memberCtx, memberToken, closureSlot, 'A2328休業対象(会員)')
  await reserve(ownerCtx, ownerToken, closureSlot, 'A2328休業対象(管理者)')
  // キャンセル待ち: 管理者が満席にし、会員が画面で待機登録する
  waitSlot = await createTemplateAndFindSlot(waitDay, '09:00', 1)
  await confirm(await reserve(ownerCtx, ownerToken, waitSlot, 'A2328満席化'))
  // リマインド: 会員の確定済み予約
  const remindSlot = await createTemplateAndFindSlot(remindDay, '11:00', 2)
  remindReservationId = await reserve(memberCtx, memberToken, remindSlot, 'A2328リマインド対象')
  await confirm(remindReservationId)
  // 強制キャンセル: 会員の予約（定期予約不可枠 09:00-11:00 と衝突させる）
  const forceSlot = await createTemplateAndFindSlot(forceDay, '09:00', 1)
  await reserve(memberCtx, memberToken, forceSlot, 'A2328強行対象')
})

test.afterAll(async () => {
  for (const c of sessions) await c.close()
  try {
    // このテストが作った使い捨てチーム scope の通知だけを ID 指定で削除する
    const out = dbScalar(`select id from notifications where id > ${baseline.id} and scope_type = 'TEAM' and scope_id = ${team.id}`)
    const ids = out ? out.split(/\s+/).map(Number) : []
    if (ids.length > 0) {
      dbScalar(`delete from notifications where id in (${ids.join(',')})`)
      console.log(`[後始末] 通知 ${ids.length} 件を ID 指定で削除: ${ids.join(',')}`)
    }
  } catch (e) { console.log('[後始末] 通知削除失敗', String(e)) }
  try {
    // 緊急休業の履歴（と確認行）も、使い捨てチームのものだけを削除する
    dbScalar(`delete from emergency_closure_confirmations where emergency_closure_id in (select id from emergency_closures where team_id = ${team.id})`)
    dbScalar(`delete from emergency_closures where team_id = ${team.id}`)
    console.log(`[後始末] 緊急休業履歴を削除: team_id=${team.id}`)
  } catch (e) { console.log('[後始末] 緊急休業履歴の削除失敗', String(e)) }
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

async function openReservations(page: Page): Promise<void> {
  await gotoHydrated(page, `/teams/${team.slug}/reservations`)
}

/**
 * 通知一覧で title の通知をクリックし、/teams/{slug}/reservations に着地して予約画面が出ることを確かめる。
 * 一覧は新しい順なので、最新の該当通知（= このテストが直前に発火させたもの）が先頭に来る。
 */
async function clickNotificationAndLand(page: Page, title: string | RegExp, shot: string, notificationType: string): Promise<void> {
  // 発火した通知の actionUrl を DB で裏取り（画面判定の補助）
  const ids = notificationIdsSince(baseline.id, memberId, notificationType, { scopeId: team.id })
  expect(ids.length, `${notificationType} の通知が作られている`).toBeGreaterThan(0)
  const actionUrl = dbScalar(`select action_url from notifications where id = ${ids[ids.length - 1]}`)
  console.log(`[${notificationType}] 通知ID=${ids.join(',')} action_url=${actionUrl}`)
  expect(actionUrl).toBe(`/teams/${team.slug}/reservations`)

  let item = page.getByText(title).first()
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    await gotoHydrated(page, '/notifications')
    for (let i = 0; i < 4; i++) {
      if (await item.isVisible().catch(() => false)) break
      const more = page.getByRole('button', { name: 'もっと読む' })
      if (!(await more.isVisible().catch(() => false))) break
      await more.click(); await page.waitForTimeout(1_500)
    }
    if (await item.isVisible().catch(() => false)) break
    item = page.getByText(title).first()
  }
  await expect(item).toBeVisible({ timeout: 10_000 })
  await page.screenshot({ path: `test-results/${shot}-1-list.png`, fullPage: true })
  await item.click()
  await page.waitForURL(new RegExp(`/teams/${team.slug}/reservations$`), { timeout: 60_000 })
  expect(new URL(page.url()).pathname).toBe(`/teams/${team.slug}/reservations`)
  expect(new URL(page.url()).pathname).not.toMatch(/\/teams\/\d+\//)
  await waitHydratedReservations(page)
  await expect(page.getByText('チームが見つかりません')).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '予約する' })).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: `test-results/${shot}-2-landed.png`, fullPage: true })
}

async function waitHydratedReservations(page: Page): Promise<void> {
  await page.waitForFunction(() => document.querySelector('#__nuxt')?.childElementCount, undefined, { timeout: 30_000 })
}

// ------------------------------------------------------------------
// E2E-2328-1a 強制キャンセル（管理者が定期予約不可枠を強行登録 → 会員の予約がキャンセル）
// ------------------------------------------------------------------
test('E2E-2328-1a: 強制キャンセル通知 — 管理者が画面で強行登録 → 会員が通知一覧から1タップで予約画面へ', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await openReservations(page)
  const manageTab = page.getByRole('tab', { name: '予約対象の管理' })
  await expect(manageTab).toBeVisible({ timeout: 30_000 })
  await manageTab.click()
  // 管理タブはカード方式。「週間スケジュール」カードを押すと下にセクションが開く
  const card = page.getByTestId('management-card-weekly_schedule').getByRole('button').first()
  await expect(card).toBeVisible({ timeout: 20_000 })
  await card.click()
  await page.getByTestId('recurring-add').click()
  const dialog = page.getByRole('dialog', { name: '予約不可を追加' })
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  await selectDropdown(page, page.getByTestId('recurring-day-select'), forceDay.weekdayJa)
  await selectDropdown(page, page.getByTestId('recurring-start-time'), '09:00')
  await selectDropdown(page, page.getByTestId('recurring-end-time'), '11:00')
  await fillInput(page.getByTestId('recurring-reason'), forceReason)
  const forceBtn = page.getByTestId('recurring-force-cancel-button')
  await expect(forceBtn).toBeVisible({ timeout: 15_000 })
  await page.screenshot({ path: 'test-results/a2328-1a-owner-impact.png', fullPage: true })
  await forceBtn.click()
  const confirmDialog = page.locator('.p-confirmdialog')
  await expect(confirmDialog).toBeVisible({ timeout: 5_000 })
  await confirmDialog.getByRole('button', { name: /キャンセルして登録する/ }).click()
  await expect(page.getByText(/(\d+)件の予約をキャンセルして登録しました/).first()).toBeVisible({ timeout: 20_000 })

  await expect.poll(
    () => notificationIdsSince(baseline.id, memberId, 'RESERVATION_CANCELLED', { scopeId: team.id }).length,
    { timeout: 60_000, message: '強制キャンセル通知が作成される' },
  ).toBeGreaterThan(0)

  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await clickNotificationAndLand(mem.page, 'ご予約がキャンセルされました', 'a2328-1a', 'RESERVATION_CANCELLED')
})

// ------------------------------------------------------------------
// E2E-2328-1b キャンセル待ち空き通知
// ------------------------------------------------------------------
test('E2E-2328-1b: キャンセル待ち空き通知 — 会員が画面で待機登録、管理者が画面でキャンセル → 通知から予約画面へ', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  const mp = mem.page
  await openReservations(mp)
  await mp.getByRole('tab', { name: '予約する' }).click()
  await expect(mp.getByText('メニューで絞り込む')).toBeVisible({ timeout: 30_000 })
  const weekBtn = mp.getByRole('button', { name: /^週 /, exact: false })
  const nextWeekBtn = mp.locator('button').filter({ has: mp.locator('.pi-angle-right') }).first()
  for (let i = 0; i < 8; i++) {
    const text = (await weekBtn.textContent()) ?? ''
    const m = text.match(/(\d{4})\/(\d{2})\/(\d{2}) - (\d{4})\/(\d{2})\/(\d{2})/)
    if (m && waitDay.iso >= `${m[1]}-${m[2]}-${m[3]}` && waitDay.iso <= `${m[4]}-${m[5]}-${m[6]}`) break
    await nextWeekBtn.click(); await mp.waitForTimeout(500)
  }
  const toggle = mp.getByTestId(`matrix-toggle-date-${waitDay.iso}`)
  await expect(toggle).toBeVisible({ timeout: 30_000 })
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
  const cell = mp.getByRole('button', { name: new RegExp(`${waitDay.rowLabel.replace(/[()]/g, '\\$&')} 09:00 面談室`) })
  await expect(cell).toBeVisible({ timeout: 20_000 })
  await cell.click()
  await mp.getByTestId('waitlist-register').click()
  await expect(mp.getByText(/登録しました/).first()).toBeVisible({ timeout: 15_000 })

  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const op = owner.page
  await openReservations(op)
  await op.getByRole('tab', { name: '予約一覧' }).click()
  const targetRow = op.getByRole('row').filter({ hasText: waitDay.iso })
  const cancelBtn = targetRow.locator('button:has(.pi-ban)').first()
  await expect(cancelBtn).toBeVisible({ timeout: 30_000 })
  await cancelBtn.click()
  await op.locator('.p-confirmdialog .p-button-danger').click()
  await expect(op.getByText(/キャンセルしました|キャンセル/).first()).toBeVisible({ timeout: 15_000 })

  await expect.poll(
    () => notificationIdsSince(baseline.id, memberId, 'RESERVATION_WAITLIST_OPENING', { scopeId: team.id }).length,
    { timeout: 60_000, message: '空き通知が作成される' },
  ).toBeGreaterThan(0)
  await clickNotificationAndLand(mp, 'キャンセルが出ました', 'a2328-1b', 'RESERVATION_WAITLIST_OPENING')
})

// ------------------------------------------------------------------
// E2E-2328-1c 予約リマインド
// ------------------------------------------------------------------
test('E2E-2328-1c: 予約リマインド — SYSTEM_ADMIN が画面でバッチ実行 → 会員が通知から予約画面へ', async ({ browser }) => {
  // skip の理由: 「予約リマインド配信」バッチは全テナントが対象で、使い捨てチームの予約だけに絞って起動する経路が無い。
  // 共有 BE・共有 DB で実行すると、期限の来た既存のリマインド行を持つテスト外の利用者にも通知が飛ぶ。
  // そのため、専用 DB の環境（E2E_DEDICATED_DB=1）でだけ実行する。
  // 2026-10-30 の共有検証環境での実測では、このテストは PASS している（証跡は PR #4237 本文）。
  test.skip(process.env.E2E_DEDICATED_DB !== '1', '全テナント対象のバッチを起動するため、専用 DB（E2E_DEDICATED_DB=1）でだけ実行する')
  // 前提(API): 間もなく到来するリマインド行を作る
  const remindAt = new Date(Date.now() + 15_000).toISOString().slice(0, 19)
  const mk = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservations/${remindReservationId}/reminders`, { headers: authHeaders(ownerToken), data: { remindAt } })
  console.log(`[前提] reminder create status=${mk.status()}`)
  expect(mk.ok()).toBeTruthy()
  await new Promise(r => setTimeout(r, 17_000))

  const sys = await openSession(browser, SYSADMIN); sessions.push(sys.context)
  await gotoHydrated(sys.page, '/system-admin/batches')
  const runBtn = sys.page.locator('[data-test="run-sync-reservation-reminder-dispatch"]')
  await expect(runBtn).toBeVisible({ timeout: 60_000 })
  const trig = sys.page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/system-admin/batch/reservation-reminder-dispatch/trigger'))
  await runBtn.click()
  expect((await trig).status()).toBeLessThan(300)

  await expect.poll(
    () => notificationIdsSince(baseline.id, memberId, 'RESERVATION_REMINDER', { scopeId: team.id }).length,
    { timeout: 90_000, message: 'リマインド通知が作成される' },
  ).toBeGreaterThan(0)
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await clickNotificationAndLand(mem.page, '予約リマインド', 'a2328-1c', 'RESERVATION_REMINDER')
})

// ------------------------------------------------------------------
// E2E-2329-1 緊急休業の一括送信（件数表示）
// ------------------------------------------------------------------
test('E2E-2329-1: ADMIN が緊急休業を画面で一括送信 → トースト・送信履歴に実際の宛先数(2件)が出る', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await openReservations(page)
  await page.getByRole('tab', { name: '緊急休業' }).click()
  const dates = page.locator('input[type="date"]:visible')
  await dates.nth(0).fill(closureDay.iso)
  await dates.nth(1).fill(closureDay.iso)
  await page.getByRole('button', { name: '設備メンテナンス' }).click()
  const msgSection = page.locator('section').filter({ hasText: '件名' }).filter({ hasText: '本文' }).last()
  await msgSection.locator('input').first().fill(closureSubject)
  await msgSection.locator('textarea').fill(`A2328 実機E2E: ${closureDay.iso} は休業します。`)
  await page.getByRole('button', { name: '一括送信' }).first().click()
  const sendResp = page.waitForResponse(r => r.request().method() === 'POST' && /emergency-closures?$/.test(new URL(r.url()).pathname))
  await page.locator('.p-dialog').getByRole('button', { name: '送信する' }).click()
  const sr = await sendResp
  const bodyText = await sr.text()
  console.log(`[E2E-2329-1] closure POST status=${sr.status()} ${bodyText.slice(0, 300)}`)
  expect(sr.status()).toBeLessThan(300)

  // トースト（件数は 0 でも空でもなく、実際の宛先数 = 予約した 2 人）
  const toast = page.locator('.p-toast').getByText('2件に送信しました')
  await expect(toast.first()).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: 'test-results/a2328-2329-1-toast.png', fullPage: true })
  // 画面内の結果表示（トーストと別に常設されるバナー）
  await expect(page.getByText('2件に送信しました').first()).toBeVisible()
  await expect(page.getByText(/undefined|NaN/)).toHaveCount(0)

  // 送信履歴: 今回の休業理由の行に「2件」が出て、undefined を含まない
  // 履歴の休業期間は「10月31日（土）」形式で表示される（使い捨てチームなので履歴は今回の1行のみ）
  const m = Number(closureDay.iso.slice(5, 7)); const d = Number(closureDay.iso.slice(8, 10))
  const historyRow = page.getByRole('row').filter({ hasText: `${m}月${d}日` }).first()
  await expect(historyRow).toBeVisible({ timeout: 30_000 })
  await expect(historyRow).toContainText('2件')
  const rowText = (await historyRow.textContent()) ?? ''
  expect(rowText).not.toMatch(/undefined|NaN|null/)
  console.log(`[E2E-2329-1] 履歴行="${rowText.replace(/\s+/g, ' ').trim()}"`)
  await page.screenshot({ path: 'test-results/a2328-2329-1-history.png', fullPage: true })
  // 実 DB の送信数とも一致（補助）
  expect(dbScalar(`select sent_count from emergency_closures where team_id = ${team.id} order by id desc limit 1`)).toBe('2')
})

// ------------------------------------------------------------------
// E2E-2328-2 / E2E-2329-2 / E2E-SYS 負の視点
// ------------------------------------------------------------------
test('E2E-2329-2: 一般メンバーに緊急休業の導線が出ない（URL 直打ちでも）', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await openReservations(mem.page)
  await expect(mem.page.getByRole('tab', { name: '予約する' })).toBeVisible({ timeout: 30_000 })
  await expect(mem.page.getByRole('tab', { name: '緊急休業' })).toHaveCount(0)
  await expect(mem.page.getByRole('button', { name: '一括送信' })).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/a2328-2329-2-member.png', fullPage: true })
  // 管理画面ルートの直打ち（予約の管理者ページ）
  await mem.page.goto(`/teams/${team.slug}/admin/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  // 管理コンソールの middleware（admin-console）は、権限不足をスコープトップへ戻し、エラートースト
  // 「アクセスできません／管理者権限が必要です」を出す。白画面や 500 で合格しないよう、そのトーストと着地先を確かめる。
  await expect(mem.page.getByText('管理者権限が必要です').first()).toBeVisible({ timeout: 60_000 })
  await mem.page.waitForURL(new RegExp(`/teams/${team.slug}/?$`), { timeout: 30_000 })
  console.log(`[E2E-2329-2] member admin直打ち 最終URL=${mem.page.url()}`)
  await expect(mem.page.getByRole('tab', { name: '緊急休業' })).toHaveCount(0)
  await expect(mem.page.getByRole('button', { name: '一括送信' })).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/a2328-2329-2-member-admin-url.png', fullPage: true })
})

test('E2E-2328-2 / E2E-2329-2: 部外者は /teams/{slug}/reservations を直打ちしても拒否される', async ({ browser }) => {
  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  const page = out.page
  await page.goto(`/teams/${team.slug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await page.waitForTimeout(8_000)
  const url = page.url()
  const text = ((await page.locator('body').innerText()) ?? '').replace(/\s+/g, ' ').slice(0, 400)
  console.log(`[E2E-2328-2] 部外者 直打ち 最終URL=${url} 本文先頭="${text}"`)
  await page.screenshot({ path: 'test-results/a2328-2-outsider-direct-url.png', fullPage: true })
  // 実測で出た拒否画面の文言（白画面・500 では合格しない）
  await expect(page.getByText(DENIED_TEXT).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('tab', { name: '予約する' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '緊急休業' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '一括送信' })).toHaveCount(0)
})

test('E2E-SYS: SYSTEM_ADMIN の観測 — 緊急休業 API は AccessGuard の設計どおり通る（読み取りで確認）。画面の導線を記録する', async ({ browser }) => {
  // AccessGuard.isScopeAdmin は SYSTEM_ADMIN を常に通す設計（§3.3.1）。ここは実際に送信せず、読み取り(GET)だけで確かめる。
  const sysCtx = await pwRequest.newContext()
  const sysToken = await apiLogin(sysCtx, SYSADMIN)
  const list = await sysCtx.get(`${BE_API}/teams/${team.slug}/emergency-closures`, { headers: authHeaders(sysToken) })
  console.log(`[E2E-SYS] SYSTEM_ADMIN GET emergency-closures status=${list.status()}`)
  await sysCtx.dispose()

  const sys = await openSession(browser, SYSADMIN); sessions.push(sys.context)
  await sys.page.goto(`/teams/${team.slug}/reservations`, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await sys.page.waitForTimeout(10_000)
  const tabCount = await sys.page.getByRole('tab', { name: '緊急休業' }).count()
  const bookTab = await sys.page.getByRole('tab', { name: '予約する' }).count()
  console.log(`[E2E-SYS] 画面 最終URL=${sys.page.url()} 予約するタブ=${bookTab} 緊急休業タブ=${tabCount}`)
  await sys.page.screenshot({ path: 'test-results/a2328-sys.png', fullPage: true })
  // 一括送信は実行しない（実際に宛先へ通知が出るため）。実測した状態を断定する。
  expect(list.status()).toBe(200)
  expect(bookTab).toBe(1)
  expect(tabCount).toBe(1)
  await expect(sys.page.getByText(DENIED_TEXT)).toHaveCount(0)
  await expect(sys.page.getByText('チームが見つかりません')).toHaveCount(0)
})
