/**
 * CMP-260827-1152 / #2997 G5: 権限移譲(オーナー委譲)の打診・辞退 の付随通知 実機E2E。
 *
 * OWN-G5-1  管理者が members 画面から打診 → 対象メンバーの通知一覧に「管理者就任の打診が届きました」
 * OWN-G5-2  対象メンバーが打診カードで「辞退」→ 発行者の通知一覧に「オーナー委譲の打診が辞退されました」
 * OWN-G5-3  負の視点: 一般メンバーに打診導線が出ない / 他テナントは members URL 直打ちで管理導線なし
 *
 * 前提(API): 対象メンバーの TOTP 2FA 有効化（打診の前提条件）。辞退は 2FA 不要なので、
 *           辞退前にパスワードログインできるよう 2FA 行を除去する（後始末でも除去）。
 */
import { expect, test, request as pwRequest, type APIRequestContext, type BrowserContext } from '@playwright/test'
import { execSync } from 'child_process'
import { NobleCryptoPlugin, ScureBase32Plugin, TOTP } from 'otplib'
import {
  BE_API, MEMBER, OUTSIDER, OWNER, apiLogin, apiMe, authHeaders, createThrowawayTeam, dbScalar, deleteNotificationsByIds, deleteTeam,
  expectNotificationOnScreen, gotoHydrated, joinAsMember, maxNotificationId, openSession,
} from './helpers/g3457-notify'

test.describe.configure({ mode: 'serial' })

let ownerCtx: APIRequestContext
let memberCtx: APIRequestContext
let ownerToken = ''
let memberToken = ''
let memberId = 0
let memberDisplayName = ''
let team: { slug: string; id: number; name: string }
let offerId = ''
const sessions: BrowserContext[] = []

function removeTwoFactor(userId: number): void {
  execSync(`wsl.exe -e docker exec mannschaft-mysql mysql -uroot -proot mannschaft -e "DELETE FROM two_factor_auth WHERE user_id = ${userId};"`, { stdio: 'pipe' })
}

let baseline = 0

test.beforeAll(async () => {
  baseline = maxNotificationId()
  ownerCtx = await pwRequest.newContext()
  memberCtx = await pwRequest.newContext()
  ownerToken = await apiLogin(ownerCtx, OWNER)
  memberToken = await apiLogin(memberCtx, MEMBER)
  memberId = (await apiMe(memberCtx, memberToken)).id
  team = await createThrowawayTeam(ownerCtx, ownerToken, 'owner')
  expect(await joinAsMember(ownerCtx, ownerToken, memberCtx, memberToken, team.slug)).toBe('MEMBER')
  const members = await ownerCtx.get(`${BE_API}/teams/${team.slug}/members?page=0&size=100`, { headers: authHeaders(ownerToken) })
  const list = (await members.json()).data as Array<{ userId: number; displayName: string }>
  memberDisplayName = list.find(m => m.userId === memberId)?.displayName ?? ''
  expect(memberDisplayName).toBeTruthy()

  // 前提: 対象の強い2FA(TOTP)を有効化
  const setup = await memberCtx.post(`${BE_API}/auth/2fa/setup`, { headers: authHeaders(memberToken) })
  expect(setup.status(), await setup.text()).toBe(201)
  const secret = (await setup.json()).data.secret as string
  const code = await new TOTP({ secret, crypto: new NobleCryptoPlugin(), base32: new ScureBase32Plugin() }).generate()
  const verify = await memberCtx.post(`${BE_API}/auth/2fa/verify`, { headers: authHeaders(memberToken), data: { totpCode: code } })
  expect(verify.status(), await verify.text()).toBe(200)
})

test.afterAll(async () => {
  for (const c of sessions) await c.close().catch(() => {})
  try { removeTwoFactor(memberId) } catch (e) { console.log('[後始末] 2FA 除去失敗', String(e)) }
  try {
    // 打診・辞退の通知（このテスト専用チーム scope_id かつ baseline 以降）だけを ID 指定で削除
    const ids = dbScalar(`select id from notifications where id > ${baseline} and scope_id = ${team?.id ?? 0} and notification_type in ('OWNERSHIP_TRANSFER_OFFERED','OWNERSHIP_TRANSFER_DECLINED')`)
    deleteNotificationsByIds(ids ? ids.split(/\s+/).map(Number) : [])
  } catch (e) { console.log('[後始末] 通知削除失敗', String(e)) }
  if (team) await deleteTeam(ownerCtx, ownerToken, team.slug)
  await ownerCtx.dispose(); await memberCtx.dispose()
})

test('OWN-G5-1: 管理者が画面で打診 → 対象メンバーの通知一覧に届く', async ({ browser }) => {
  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  const page = owner.page
  await gotoHydrated(page, `/teams/${team.slug}/members`)
  await page.getByTestId('transfer-ownership-open').click()
  await expect(page.getByTestId('transfer-ownership-dialog')).toBeVisible()
  await page.getByTestId('transfer-ownership-target').click()
  await page.locator('.p-select-option').filter({ hasText: memberDisplayName }).click()
  await page.getByTestId('transfer-ownership-confirm').fill(team.name)
  const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith(`/teams/${team.slug}/transfer-ownership-offers`))
  await page.getByTestId('transfer-ownership-submit').click()
  const resp = await created
  expect(resp.status(), await resp.text()).toBe(201)
  offerId = (await resp.json()).data.offerId
  await expect(page.getByTestId('transfer-ownership-pending')).toContainText(memberDisplayName)
  await page.screenshot({ path: 'test-results/g3457-own-1-owner-pending.png', fullPage: true })

  // 2FA を除去してパスワードログイン可能にしてから受信者視点へ
  removeTwoFactor(memberId)
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await expectNotificationOnScreen(mem.page, '管理者就任の打診が届きました', { shot: 'g3457-own-1-member-notification' })
})

test('OWN-G5-2: 対象メンバーが打診カードで辞退 → 発行者の通知一覧に届き、ロールは不変', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  const page = mem.page
  await gotoHydrated(page, `/teams/${team.slug}/members?offerId=${offerId}`)
  await expect(page.getByText(`${team.name} の管理者への就任を打診されています`)).toBeVisible({ timeout: 30_000 })
  const declined = page.waitForResponse(r => r.request().method() === 'POST' && r.url().includes(`/transfer-ownership-offers/${offerId}/decline`))
  await page.getByRole('button', { name: /辞退/ }).first().click()
  await page.locator('.p-confirmdialog').getByRole('button', { name: 'はい', exact: true }).click()
  const r = await declined
  expect(r.status(), await r.text()).toBeLessThan(300)
  await page.screenshot({ path: 'test-results/g3457-own-2-member-declined.png', fullPage: true })

  const owner = await openSession(browser, OWNER); sessions.push(owner.context)
  await expectNotificationOnScreen(owner.page, 'オーナー委譲の打診が辞退されました', { shot: 'g3457-own-2-owner-notification' })
  // ロール不変: 発行者は引き続き管理者（打診導線が出る）
  await gotoHydrated(owner.page, `/teams/${team.slug}/members`)
  await expect(owner.page.getByTestId('transfer-ownership-open')).toBeVisible()
})

test('OWN-G5-3: 一般メンバーに打診導線なし / 他テナントは members URL 直打ちでも管理導線なし', async ({ browser }) => {
  const mem = await openSession(browser, MEMBER); sessions.push(mem.context)
  await gotoHydrated(mem.page, `/teams/${team.slug}/members`)
  await mem.page.waitForTimeout(3_000)
  await expect(mem.page.getByTestId('transfer-ownership-open')).toHaveCount(0)
  await mem.page.screenshot({ path: 'test-results/g3457-own-3-member-no-entry.png', fullPage: true })
  const direct = await memberCtx.post(`${BE_API}/teams/${team.slug}/transfer-ownership-offers`, { headers: authHeaders(memberToken), data: { targetUserId: memberId } })
  expect([400, 401, 403, 404, 422]).toContain(direct.status())

  const out = await openSession(browser, OUTSIDER); sessions.push(out.context)
  await gotoHydrated(out.page, `/teams/${team.slug}/members`)
  await out.page.waitForTimeout(3_000)
  await expect(out.page.getByTestId('transfer-ownership-open')).toHaveCount(0)
  await out.page.screenshot({ path: 'test-results/g3457-own-3-outsider-direct-url.png', fullPage: true })
})
