import { expect, test, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// CMP-260930-1932 実機E2E: システム発の確認通知（最終認証 / 自動キャンセル）を
// 組織の通知クレジット課金から外した変更の検証。
// 対象組織 organization_id=9（team_id=1 "fc-u-18"）は、事前に DB で
// 通知クレジット枯渇状態（grace_period_start_at が 72h 超過・残高0）にしてある
// （手順は本PRのレビュー報告を参照。DML は殿の承認済み・残置可）。

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const MEMBER = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const OUTSIDER = process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'

// 事前に用意した確認通知（本レポート記載の手順で作成。id は環境依存のため env で上書き可能）
const MARKET_FINALIZE_NOTIFICATION_ID = Number(process.env.CMP1932_MARKET_NOTIFICATION_ID ?? '80')
const MARKET_FINALIZE_RECIPIENT_ID = Number(process.env.CMP1932_MARKET_RECIPIENT_ID ?? '138')
const AUTO_CANCEL_NOTIFICATION_ID = Number(process.env.CMP1932_AUTOCANCEL_NOTIFICATION_ID ?? '81')
const AUTO_CANCEL_RECIPIENT_ID = Number(process.env.CMP1932_AUTOCANCEL_RECIPIENT_ID ?? '139')
const ORG_ID = Number(process.env.CMP1932_ORG_ID ?? '9')

async function loginForRealDevice(page: Page, email: string) {
  await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
  const pageHost = new URL(process.env.BASE_URL ?? 'http://localhost:3001').hostname
  const apiHost = new URL(API_BASE).hostname
  if (pageHost === apiHost) return

  const apiCookies = await page.context().cookies(API_BASE)
  await page.context().addCookies(apiCookies.map(cookie => ({ ...cookie, domain: pageHost })))
}

async function waitForPageHydration(page: Page) {
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 60_000 },
  )
}

test('E2E-1: 通知クレジット枯渇組織でも市募集の最終認証通知が管理者の確認通知一覧に見える', async ({ page }) => {
  await loginForRealDevice(page, ADMIN)
  await page.goto('/notifications', { waitUntil: 'commit' })
  await waitForPageHydration(page)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  // 汎用 /api/v1/notifications は長期運用の蓄積データでページング1枚目に乗らないことがあるため、
  // クレジット枯渇組織でも MARKET_FINALIZE 通知が届いていることは
  // 確認通知サービスの self-scoped pending API（受信者行の実在）で確認する。
  const pending = await page.request.get(`${API_BASE}/api/v1/me/confirmable-notifications/pending`)
  expect(pending.status(), `確認通知pending一覧API: ${await pending.text()}`).toBe(200)
  const pendingBody = (await pending.json()) as { data: Array<{ id: number }> }
  expect(
    pendingBody.data.some(item => item.id === MARKET_FINALIZE_RECIPIENT_ID),
    `pending一覧に最終認証通知の受信者行(id=${MARKET_FINALIZE_RECIPIENT_ID})が含まれない: ${JSON.stringify(pendingBody)}`,
  ).toBe(true)
})

test('E2E-1 ロール横断: 最終認証の受信対象外メンバーには表示されない', async ({ page }) => {
  await loginForRealDevice(page, MEMBER)
  await page.goto('/notifications', { waitUntil: 'commit' })
  await waitForPageHydration(page)
  // e2e-user はこの募集の最終認証対象（ADMIN ロール）ではないため表示されない
  await expect(page.getByText('募集を確定して札を下げますか？')).toHaveCount(0)
})

test('E2E-1 ロール横断: 他テナント利用者は組織管理APIで403/404相当になる', async ({ page }) => {
  await loginForRealDevice(page, OUTSIDER)
  const res = await page.request.get(
    `${API_BASE}/api/v1/organizations/${ORG_ID}/confirmable-notifications/${MARKET_FINALIZE_NOTIFICATION_ID}`,
  )
  expect([403, 404]).toContain(res.status())
})

test('E2E-2: 通知クレジット枯渇組織でも自動キャンセル通知が対象者の確認通知一覧に見える', async ({ page }) => {
  await loginForRealDevice(page, MEMBER)
  await page.goto('/notifications', { waitUntil: 'commit' })
  await waitForPageHydration(page)

  // 汎用 /api/v1/notifications は長期運用で蓄積した他スコープの通知が大量に混在し
  // （本検証でも e2e-user 宛の総件数は40万件超）、ページング1枚目に乗らない場合がある。
  // 確認通知そのものの到達確認は、確認通知サービスの self-scoped pending API で行う。
  const pending = await page.request.get(`${API_BASE}/api/v1/me/confirmable-notifications/pending`)
  expect(pending.status(), `確認通知pending一覧API: ${await pending.text()}`).toBe(200)
  const pendingBody = (await pending.json()) as { data: Array<{ id: number }> }
  expect(
    pendingBody.data.some(item => item.id === AUTO_CANCEL_RECIPIENT_ID),
    `pending一覧に自動キャンセル通知の受信者行(id=${AUTO_CANCEL_RECIPIENT_ID})が含まれない: ${JSON.stringify(pendingBody)}`,
  ).toBe(true)
})

test('E2E-2 ロール横断: 自動キャンセル通知の対象外（他テナント）は直打ちで403/404相当', async ({ page }) => {
  await loginForRealDevice(page, OUTSIDER)
  const res = await page.request.get(
    `${API_BASE}/api/v1/organizations/${ORG_ID}/confirmable-notifications/${AUTO_CANCEL_NOTIFICATION_ID}`,
  )
  expect([403, 404]).toContain(res.status())
})

test('E2E-2 ロール横断: 自分宛でない確認通知はme APIのconfirmで操作できない', async ({ page }) => {
  // ADMIN(24)は自動キャンセル通知の受信者ではない（受信者は募集参加者のみ）。
  // self-scoped の confirm API がなりすまし操作を拒否することを確認する。
  await loginForRealDevice(page, ADMIN)
  const res = await page.request.post(
    `${API_BASE}/api/v1/me/confirmable-notifications/${AUTO_CANCEL_NOTIFICATION_ID}/confirm`,
  )
  expect([400, 403, 404]).toContain(res.status())
})
