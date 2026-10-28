/**
 * CMP-260827-1152 / #2997 G7-a(M8): チーム時間割コマの共通メモ更新通知（AFTER_COMMIT＋5分デバウンス）実機E2E。
 *
 * 【到達性の欠陥】チーム時間割のコマ(メモ)編集 API（PUT /api/v1/timetables/{id}/slots）を呼ぶ画面が存在しない
 *   （useTimetableApi.updateSlots の呼び出し元が FE に無い。/teams/{slug}/timetable は閲覧・作成・臨時変更のみ）。
 *   よって「対象操作」を画面で行えない。操作のみ API 補助で行い、通知の到達は受信者の通知一覧「画面」で確認する。
 *
 * TT-G7-1  管理者が(API補助で)コメモを更新 → チームメンバーの通知一覧に「共通メモが更新されました」
 * TT-G7-2  同じコマを5分以内に再更新しても通知は増えない（M8 デバウンス実効化。DB 件数で裏取り）
 * TT-G7-3  負の視点: 他テナントには通知が出ない／管理画面にメモ編集導線がない（欠陥の画面証拠）
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext } from '@playwright/test'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, apiLogin, apiMe, authHeaders, createThrowawayTeam, dbScalar,
  deleteNotificationsByIds, deleteTeam, expectNotificationOnScreen, expectNoNotificationOnScreen, gotoHydrated,
  joinAsMember, maxNotificationId, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })
test.setTimeout(420_000)

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let memberId = 0
let team: { slug: string; id: number; name: string }
let timetableId = 0
const sessions: BrowserContext[] = []
let baseline = 0
const subject = `G3457数学${Date.now() % 100000}`
const noteMarker = `G3457メモ${Date.now()}`

async function putSlots(notes: string): Promise<void> {
  const send = () => ownerCtx.put(`${BE_API}/timetables/${timetableId}/slots`, {
    headers: authHeaders(ownerToken),
    data: { slots: [{ dayOfWeek: 'MON', periodNumber: 1, weekPattern: 'EVERY', subjectName: subject, notes }] },
  })
  // 長時間アイドル後の keep-alive 切断(socket hang up)に限り 1 回だけ再送する（業務結果の失敗は再送しない）
  const r = await send().catch(async (e: Error) => {
    if (!/socket hang up|ECONNRESET/.test(e.message)) throw e
    return send()
  })
  expect(r.status(), await r.text()).toBe(200)
}

const noteCount = () =>
  Number(dbScalar(`select count(*) from notifications where user_id=${memberId} and id > ${baseline} and notification_type='TEAM_SLOT_NOTE_UPDATED' and scope_id=${team.id}`))

test.beforeAll(async () => {
  baseline = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  memberId = (await apiMe(memberCtx, memberToken)).id
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'timetable')
  expect(await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)).toBe('MEMBER')
  const cat = await ownerCtx.get(`${BE_API}/teams/${team.slug}/modules/catalog`, { headers: authHeaders(ownerToken) })
  const mods = ((await cat.json()).data.modules as Array<{ moduleId: number; slug: string }>)
  const mod = mods.find(m => m.slug === 'timetable')
  if (mod) {
    const tg = await ownerCtx.patch(`${BE_API}/teams/${team.slug}/modules/${mod.moduleId}/toggle`, { headers: authHeaders(ownerToken), data: { moduleId: mod.moduleId, enabled: true } })
    console.log(`[前提] timetable module toggle status=${tg.status()}`)
  } else {
    console.log(`[前提] timetable モジュールはカタログに無い: ${mods.map(m => m.slug).join(',')}`)
  }
  const term = await ownerCtx.post(`${BE_API}/teams/${team.slug}/timetable-terms`, {
    headers: authHeaders(ownerToken), data: { name: 'G3457学期', startDate: '2026-04-01', endDate: '2027-03-31', academicYear: 2026, sortOrder: 0 },
  })
  expect(term.status(), await term.text()).toBeLessThan(300)
  const termId = (await term.json()).data.id
  const tt = await ownerCtx.post(`${BE_API}/teams/${team.slug}/timetables`, {
    headers: authHeaders(ownerToken), data: { name: 'G3457時間割', termId, effectiveFrom: '2026-04-01', visibility: 'MEMBERS_ONLY' },
  })
  expect(tt.status(), await tt.text()).toBeLessThan(300)
  timetableId = (await tt.json()).data.id
})

test.afterAll(async () => {
  for (const c of sessions) await c.close().catch(() => {})
  try {
    const ids = dbScalar(`select id from notifications where id > ${baseline} and notification_type='TEAM_SLOT_NOTE_UPDATED' and scope_id=${team?.id ?? 0}`)
    deleteNotificationsByIds(ids ? ids.split(/\s+/).map(Number) : [])
  } catch (e) { console.log('[後始末] 通知削除失敗', String(e)) }
  if (timetableId) await ownerCtx.delete(`${BE_API}/teams/${team.slug}/timetables/${timetableId}`, { headers: authHeaders(ownerToken) }).catch(() => {})
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

test('TT-G7-1: コマ共通メモの更新(API補助)でメンバーの通知一覧に届く', async ({ browser }) => {
  await putSlots(`${noteMarker} 1回目`)
  await expect.poll(noteCount, { timeout: 60_000, message: '通知が作成される' }).toBeGreaterThanOrEqual(1)
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, new RegExp(`${subject}`), { shot: 'g3457-tt-1-member-notification' })
  // 通知の遷移先（/teams/{id}/timetables/{id}）を実際に開いて到達できるか確認
  await mem.page.getByText(new RegExp(subject)).first().click()
  await mem.page.waitForTimeout(4_000)
  console.log(`[TT-G7-1] 通知クリック後の URL=${mem.page.url()}`)
  const deadLink = await mem.page.getByText('このページは見つかりませんでした').isVisible().catch(() => false)
  console.log(`[TT-G7-1] 通知の遷移先が 404 画面=${deadLink}`)
  test.info().annotations.push({ type: 'defect', description: `コマ共通メモ通知の遷移先(/teams/{id}/timetables/{id})は存在しないページ: 404画面=${deadLink}` })
  await mem.page.screenshot({ path: 'test-results/g3457-tt-1-member-after-click.png', fullPage: true })
})

test('TT-G7-2: 同じコマを5分以内に再更新しても通知は増えない（M8）', async () => {
  const before = noteCount()
  // 【欠陥の実証】同じコマ位置への再更新は 500（replaceSlots が delete→insert を flush せず uq_ts_timetable_day_period_week に衝突）
  const raw = await ownerCtx.put(`${BE_API}/timetables/${timetableId}/slots`, {
    headers: authHeaders(ownerToken),
    data: { slots: [{ dayOfWeek: 'MON', periodNumber: 1, weekPattern: 'EVERY', subjectName: subject, notes: `${noteMarker} 再更新` }] },
  })
  console.log(`[TT-G7-2] 同位置への再更新 status=${raw.status()}`)
  test.info().annotations.push({ type: 'defect', description: `同じコマ位置への PUT /timetables/{id}/slots 再更新の status=${raw.status()}（期待200）` })
  // デバウンス(M8)を検証するため、前提として当該時間割のコマ行を ID 指定で除去してから 2 回目の更新を行う（専用チームの行のみ）
  dbScalar(`delete from timetable_slots where timetable_id = ${timetableId}`)
  await putSlots(`${noteMarker} 2回目`)
  await new Promise(r => setTimeout(r, 12_000))
  expect(noteCount(), 'デバウンスで 2 件目は作られない').toBe(before)
})

test('TT-G7-3: 他テナントに通知が出ない／管理画面にメモ編集導線がない', async ({ browser }) => {
  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  await expectNoNotificationOnScreen(out.page, new RegExp(subject), 2_000)
  await out.page.screenshot({ path: 'test-results/g3457-tt-3-outsider-notifications.png', fullPage: true })
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  await gotoHydrated(owner.page, `/teams/${team.slug}/timetable`)
  await owner.page.waitForTimeout(4_000)
  await expect(owner.page.locator('textarea')).toHaveCount(0)
  await expect(owner.page.getByRole('button', { name: /メモ/ })).toHaveCount(0)
  await owner.page.screenshot({ path: 'test-results/g3457-tt-3-owner-timetable-no-note-editor.png', fullPage: true })
})
