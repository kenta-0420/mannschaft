/**
 * CMP-260827-1152 / #2997 G4-b: 確認通知の手動リマインド再送（業務TXから分離済み・M4: 204=受付、配送は非同期）実機E2E。
 *
 * CNF-G4-1  管理者が設定画面の送信履歴から受信者一覧を開き「リマインド再送」→ 204、未確認メンバーの通知一覧に再送通知が増える
 * CNF-G4-2  権限なし(MEMBER)・他テナントは設定URL直打ちでも再送導線が出ない
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext } from '@playwright/test'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, apiLogin, apiMe, authHeaders, createThrowawayTeam, dbScalar,
  deleteNotificationsByIds, deleteTeam, expectNotificationOnScreen, gotoHydrated, joinAsMember,
  maxNotificationId, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })
test.setTimeout(420_000)

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let memberId = 0
let team: { slug: string; id: number; name: string }
const sessions: BrowserContext[] = []
let baseline = 0
let notificationId = 0
const title = `G3457Confirm_${Date.now()}`
const settingsPath = () => `/teams/${team.slug}/settings/confirmable-notifications`

test.beforeAll(async () => {
  baseline = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  memberId = (await apiMe(memberCtx, memberToken)).id
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'confirm')
  expect(await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)).toBe('MEMBER')
  // 前提データ(API): 確認通知を1件送る
  const send = await ownerCtx.post(`${BE_API}/teams/${team.slug}/confirmable-notifications`, {
    headers: authHeaders(ownerToken),
    data: { title, body: 'G3457 実機E2E（後始末で削除）', priority: 'NORMAL', targets: [{ type: 'TEAM', id: team.id }] },
  })
  expect(send.status(), await send.text()).toBeLessThan(300)
  notificationId = (await send.json()).data.id
})

test.afterAll(async () => {
  for (const c of sessions) await c.close().catch(() => {})
  try {
    const ids = dbScalar(`select id from notifications where id > ${baseline} and (instr(title, '${title}') > 0 or instr(body, '${title}') > 0)`)
    deleteNotificationsByIds(ids ? ids.split(/\s+/).map(Number) : [])
    if (notificationId) {
      await ownerCtx.patch(`${BE_API}/teams/${team.slug}/confirmable-notifications/${notificationId}/cancel`, { headers: authHeaders(ownerToken) }).catch(() => {})
    }
  } catch (e) { console.log('[後始末] 失敗', String(e)) }
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

const countFor = (userId: number) =>
  Number(dbScalar(`select count(*) from notifications where user_id=${userId} and id > ${baseline} and (instr(title, '${title}') > 0 or instr(body, '${title}') > 0)`))

test('CNF-G4-1: 管理者が画面でリマインド再送 → 未確認メンバーの通知一覧に再送が届く', async ({ browser }) => {
  // 初回配送(非同期ファンアウト)の完了を待つ
  await expect.poll(() => countFor(memberId), { timeout: 90_000, message: '初回の確認通知が配送される' }).toBeGreaterThanOrEqual(1)
  const before = countFor(memberId)

  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await gotoHydrated(page, settingsPath())
  await expect(page.getByRole('heading', { name: '確認通知設定', level: 1 })).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button').filter({ hasText: title }).first().click() // 送信履歴の行を展開して受信者一覧へ
  const resendBtn = page.getByRole('button', { name: /再送/ })
  await expect(resendBtn).toBeVisible({ timeout: 30_000 })
  await page.screenshot({ path: 'test-results/g3457-cnf-1-owner-recipients.png', fullPage: true })
  const resp = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`/confirmable-notifications/${notificationId}/resend-reminder`))
  await resendBtn.click()
  const r = await resp
  expect(r.status()).toBe(204) // 204 は本文なし（M4: 受付。配送は非同期）
  // M4 の画面側の様子: 受付を示す表示が出るか（出なければ欠陥として報告する）
  const toastShown = await page.locator('.p-toast-message').first().isVisible({ timeout: 4_000 }).catch(() => false)
  console.log(`[CNF-G4-1] 再送 204 後に受付を示すトースト等の表示=${toastShown}`)
  test.info().annotations.push({ type: 'finding', description: `再送204後のフィードバック表示=${toastShown}` })
  await page.screenshot({ path: 'test-results/g3457-cnf-1-owner-after-resend.png', fullPage: true })

  await expect.poll(() => countFor(memberId), { timeout: 60_000, message: '再送通知が非同期で作成される' }).toBeGreaterThan(before)
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, new RegExp(title), { shot: 'g3457-cnf-1-member-notification' })
})

test('CNF-G4-2: 権限なしと他テナントには再送導線がなく、設定URL直打ちでも管理画面は出ない', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await gotoHydrated(mem.page, settingsPath())
  await mem.page.waitForTimeout(4_000)
  await expect(mem.page.getByRole('button', { name: /再送/ })).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/g3457-cnf-2-member-direct-url.png', fullPage: true })
  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  await gotoHydrated(out.page, settingsPath())
  await out.page.waitForTimeout(4_000)
  await expect(out.page.getByRole('button', { name: /再送/ })).toHaveCount(0)
  await expect(out.page.getByText(title)).toHaveCount(0)
  await out.page.screenshot({ path: 'test-results/g3457-cnf-2-outsider-direct-url.png', fullPage: true })
  const direct = await memberCtx.post(`${BE_API}/teams/${team.slug}/confirmable-notifications/${notificationId}/resend-reminder`, { headers: authHeaders(memberToken) })
  expect([401, 403, 404]).toContain(direct.status())
})
