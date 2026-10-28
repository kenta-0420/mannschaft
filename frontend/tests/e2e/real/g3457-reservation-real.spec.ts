/**
 * CMP-260827-1152 / #2997 G3: 予約ドメインの付随通知（休業・リマインド・キャンセル待ち空き通知）実機E2E。
 *
 * RSV-G3-1  休業: 管理者が「緊急休業」タブで一括送信 → 予約者の通知一覧に休業通知（件名）が出る
 * RSV-G3-2  休業の負の視点: 一般メンバーに「緊急休業」タブが出ない / 他テナントは URL 直打ちでも管理画面が出ない
 * RSV-G3-3  キャンセル待ち: 待機者(MEMBER)が画面で登録 → 予約者が画面でキャンセル → 待機者に「キャンセルが出ました」
 * RSV-G3-4  予約リマインド: SYSTEM_ADMIN が管理画面でバッチ「reservation-reminder-dispatch」を実行
 *           → 予約者の通知一覧に「予約リマインド」（M7 の SYSTEM_ADMIN 通知は g3457-admin-batch-real.spec.ts）
 * RSV-G3-1b 【欠陥の実証】「その他（手動入力）」は reason が空で 400（画面に理由入力欄がない）
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, SYSADMIN, apiLogin, apiMe, authHeaders, createThrowawayTeam, dbScalar,
  deleteNotificationsByIds, deleteTeam, expectNotificationOnScreen, gotoHydrated, joinAsMember,
  maxNotificationId, notificationIdsSince, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })
test.setTimeout(420_000)

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const
const WEEKDAY_JA = ['日', '月', '火', '水', '木', '金', '土'] as const
function jstIso(offsetDays: number): { iso: string; dayCode: (typeof DAY_CODES)[number]; rowLabel: string } {
  const d = new Date(Date.now() + 9 * 3600_000 + offsetDays * 86400_000)
  const iso = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
  return { iso, dayCode: DAY_CODES[d.getUTCDay()]!, rowLabel: `${iso.replaceAll('-', '/')} (${WEEKDAY_JA[d.getUTCDay()]})` }
}

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let memberId = 0
let team: { slug: string; id: number; name: string }
let lineId = 0
const sessions: BrowserContext[] = []
const baseline = { id: 0 }
const createdNotificationIds: number[] = []

const closureDay = jstIso(1)
const waitDay = jstIso(2)
const remindDay = jstIso(3)
let closureSlot = 0
let waitSlot = 0
let remindSlot = 0
let remindReservationId = 0
const closureSubject = `G3457休業_${Date.now()}`

async function createTemplateAndFindSlot(day: ReturnType<typeof jstIso>, start: string, capacity: number): Promise<number> {
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

test.beforeAll(async () => {
  baseline.id = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  memberId = (await apiMe(memberCtx, memberToken)).id
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'rsv')
  expect(await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)).toBe('MEMBER')

  // 予約モジュール有効化・営業時間・ライン
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

  // 前提データ: 休業対象(会員予約)・満席枠(管理者予約)・リマインド対象(会員予約+確定+リマインド)
  closureSlot = await createTemplateAndFindSlot(closureDay, '10:00', 2)
  await reserve(memberCtx, memberToken, closureSlot, 'G3457休業対象')
  waitSlot = await createTemplateAndFindSlot(waitDay, '09:00', 1)
  const ownerRsv = await reserve(ownerCtx, ownerToken, waitSlot, 'G3457満席化')
  const oc = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservations/${ownerRsv}/confirm`, { headers: authHeaders(ownerToken), data: {} })
  console.log(`[前提] 管理者予約 confirm status=${oc.status()}`)
  remindSlot = await createTemplateAndFindSlot(remindDay, '11:00', 2)
  remindReservationId = await reserve(memberCtx, memberToken, remindSlot, 'G3457リマインド対象')
  const conf = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservations/${remindReservationId}/confirm`, { headers: authHeaders(ownerToken), data: {} })
  console.log(`[前提] confirm status=${conf.status()}`)
})

test.afterAll(async () => {
  for (const c of sessions) await c.close().catch(() => {})
  try {
    // 通知: このテストで作られた(ID 指定)ものだけ削除
    deleteNotificationsByIds(createdNotificationIds)
  } catch (e) { console.log('[後始末] 通知削除失敗', String(e)) }
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

async function openReservations(page: Page): Promise<void> {
  await gotoHydrated(page, `/teams/${team.slug}/reservations`)
}

test('RSV-G3-1: 管理者が緊急休業を画面で一括送信 → 予約者の通知一覧に休業通知が出る', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await openReservations(page)
  await page.getByRole('tab', { name: '緊急休業' }).click()
  const dates = page.locator('input[type="date"]:visible')
  await dates.nth(0).fill(closureDay.iso)
  await dates.nth(1).fill(closureDay.iso)
  // 休業理由(reason)は画面に入力欄がなく、テンプレート選択でのみ入る。テンプレートを選んでから件名・本文を上書きする。
  await page.getByRole('button', { name: '設備メンテナンス' }).click()
  const msgSection = page.locator('section').filter({ hasText: '件名' }).filter({ hasText: '本文' }).last()
  await msgSection.locator('input').first().fill(closureSubject)
  await msgSection.locator('textarea').fill(`G3457 実機E2E: ${closureDay.iso} は休業します。`)
  await page.getByRole('button', { name: '一括送信' }).first().click()
  const sendResp = page.waitForResponse(r => r.request().method() === 'POST' && /emergency-closures?$/.test(new URL(r.url()).pathname))
  await page.locator('.p-dialog').getByRole('button', { name: '送信する' }).click()
  const sr = await sendResp
  console.log(`[RSV-G3-1] closure POST ${sr.url()} status=${sr.status()} ${(await sr.text()).slice(0, 300)}`)
  expect(sr.status()).toBeLessThan(300)
  await expect(page.getByText(/件に送信しました/).first()).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: 'test-results/g3457-rsv-1-owner-sent.png', fullPage: true })

  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, new RegExp(closureSubject), { shot: 'g3457-rsv-1-member-notification' })
  await expect(mem.page.getByText('緊急休業').first()).toBeVisible()
  createdNotificationIds.push(...notificationIdsSince(baseline.id, memberId, 'EMERGENCY_CLOSURE', { scopeId: team.id }))
})

test('RSV-G3-1b: 【欠陥の実証】「その他（手動入力）」では reason が空のまま送信され 400 になる（理由入力欄が画面にない）', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await openReservations(page)
  await page.getByRole('tab', { name: '緊急休業' }).click()
  const dates = page.locator('input[type="date"]:visible')
  await dates.nth(0).fill(closureDay.iso)
  await dates.nth(1).fill(closureDay.iso)
  await page.getByRole('button', { name: 'その他（手動入力）' }).click()
  const msgSection = page.locator('section').filter({ hasText: '件名' }).filter({ hasText: '本文' }).last()
  await msgSection.locator('input').first().fill('G3457手動入力')
  await msgSection.locator('textarea').fill('G3457 手動入力の本文')
  await page.getByRole('button', { name: '一括送信' }).first().click()
  const sendResp = page.waitForResponse(r => r.request().method() === 'POST' && /emergency-closures?$/.test(new URL(r.url()).pathname))
  await page.locator('.p-dialog').getByRole('button', { name: '送信する' }).click()
  const sr = await sendResp
  const text = await sr.text()
  console.log(`[RSV-G3-1b] status=${sr.status()} ${text.slice(0, 200)}`)
  await expect(page.getByText('送信に失敗しました').first()).toBeVisible({ timeout: 15_000 })
  await page.screenshot({ path: 'test-results/g3457-rsv-1b-custom-template-fails.png', fullPage: true })
  test.info().annotations.push({ type: 'defect', description: `手動入力テンプレートの休業送信は reason 必須で ${sr.status()}: ${text.slice(0, 120)}` })
  expect(sr.status()).toBe(400)
})

test('RSV-G3-2: 休業の負の視点 — 一般メンバーに導線なし・他テナントはURL直打ちでも管理画面なし', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await openReservations(mem.page)
  await mem.page.waitForTimeout(3_000)
  await expect(mem.page.getByRole('tab', { name: '緊急休業' })).toHaveCount(0)
  await expect(mem.page.getByRole('button', { name: '一括送信' })).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/g3457-rsv-2-member-no-closure-tab.png', fullPage: true })
  const direct = await memberCtx.post(`${BE_API}/teams/${team.slug}/emergency-closures`, {
    headers: authHeaders(memberToken),
    data: { startDate: closureDay.iso, endDate: closureDay.iso, reason: 'x', subject: 'x', messageBody: 'x', cancelReservations: false },
  })
  expect([401, 403, 404]).toContain(direct.status())

  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  await openReservations(out.page)
  await out.page.waitForTimeout(4_000)
  await expect(out.page.getByRole('tab', { name: '緊急休業' })).toHaveCount(0)
  await expect(out.page.getByRole('button', { name: '一括送信' })).toHaveCount(0)
  await out.page.screenshot({ path: 'test-results/g3457-rsv-2-outsider-direct-url.png', fullPage: true })
})

test('RSV-G3-3: キャンセル待ち — 待機者が画面で登録、予約者が画面でキャンセル → 待機者に空き通知', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  const mp = mem.page
  await openReservations(mp)
  await mp.getByRole('tab', { name: '予約する' }).click()
  await expect(mp.getByText('メニューで絞り込む')).toBeVisible({ timeout: 30_000 })
  // 対象日の週へ移動
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
  await mp.screenshot({ path: 'test-results/g3457-rsv-3-member-waitlisted.png', fullPage: true })

  const before = Number(dbScalar(`select count(*) from notifications where user_id=${memberId} and notification_type='RESERVATION_WAITLIST_OPENING' and id > ${baseline.id}`))
  // 予約者(OWNER)が自分の予約を画面でキャンセル
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const op = owner.page
  await openReservations(op)
  await op.getByRole('tab', { name: '予約一覧' }).click()
  // 管理者の予約一覧(team モード)では、対象日の行の「キャンセル」(pi-ban)アイコンから取り消す
  const targetRow = op.getByRole('row').filter({ hasText: waitDay.iso })
  const cancelBtn = targetRow.locator('button:has(.pi-ban)').first()
  await expect(cancelBtn).toBeVisible({ timeout: 30_000 })
  await op.screenshot({ path: 'test-results/g3457-rsv-3-owner-list.png', fullPage: true })
  await cancelBtn.click()
  await op.locator('.p-confirmdialog .p-button-danger').click()
  await expect(op.getByText(/キャンセルしました|キャンセル/).first()).toBeVisible({ timeout: 15_000 })

  await expect.poll(() => Number(dbScalar(`select count(*) from notifications where user_id=${memberId} and notification_type='RESERVATION_WAITLIST_OPENING' and id > ${baseline.id}`)), { timeout: 60_000, message: '空き通知が作成される' }).toBeGreaterThan(before)
  await expectNotificationOnScreen(mp, 'キャンセルが出ました', { shot: 'g3457-rsv-3-member-notification' })
  createdNotificationIds.push(...notificationIdsSince(baseline.id, memberId, 'RESERVATION_WAITLIST_OPENING', { scopeId: team.id }))
})

test('RSV-G3-4: 予約リマインド — SYSTEM_ADMIN がバッチを画面で実行 → 予約者の通知一覧に予約リマインドが出る', async ({ browser }) => {
  // 件数の起点は、リマインド行を作る前に取る（定期実行(1分毎)が先に配送しても取りこぼさないため）
  const beforeRemind = Number(dbScalar(`select count(*) from notifications where user_id=${memberId} and notification_type='RESERVATION_REMINDER' and scope_id=${team.id} and id > ${baseline.id}`))
  // 前提(API): 間もなく到来するリマインド行を作る
  const remindAt = new Date(Date.now() + 15_000).toISOString().slice(0, 19)
  const mk = await ownerCtx.post(`${BE_API}/teams/${team.slug}/reservations/${remindReservationId}/reminders`, { headers: authHeaders(ownerToken), data: { remindAt } })
  console.log(`[前提] reminder create status=${mk.status()} ${(await mk.text()).slice(0, 200)}`)
  expect(mk.ok()).toBeTruthy()
  await new Promise(r => setTimeout(r, 17_000))

  const sys = await openSession(browser, SYSADMIN); sessions.push(sys.context)
  await gotoHydrated(sys.page, '/system-admin/batches')
  const runBtn = sys.page.locator('[data-test="run-sync-reservation-reminder-dispatch"]')
  await expect(runBtn).toBeVisible({ timeout: 60_000 })
  await sys.page.screenshot({ path: 'test-results/g3457-rsv-4-batches.png', fullPage: true })
  const trig = sys.page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes('/system-admin/batch/reservation-reminder-dispatch/trigger'))
  await runBtn.click()
  expect((await trig).status()).toBeLessThan(300)

  await expect.poll(() => Number(dbScalar(`select count(*) from notifications where user_id=${memberId} and notification_type='RESERVATION_REMINDER' and scope_id=${team.id} and id > ${baseline.id}`)), { timeout: 90_000, message: 'リマインド通知が作成される' }).toBeGreaterThan(beforeRemind)
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, '予約リマインド', { shot: 'g3457-rsv-4-member-notification' })
  createdNotificationIds.push(...notificationIdsSince(baseline.id, memberId, 'RESERVATION_REMINDER', { scopeId: team.id }))
})
