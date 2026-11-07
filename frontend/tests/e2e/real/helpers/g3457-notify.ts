/**
 * CMP-260827-1152 / Issue #2997（付随通知の業務TX巻き込み是正）実機E2E 共通ヘルパー。
 *
 * - ログイン・前提データ作成・後始末・探索用の読み取りのみ API を使う。
 * - 「通知が届いたこと」の判定は、受信者の実ログインセッションで /notifications 画面を開き、
 *   画面上の文言で行う（API の通知一覧で代替しない）。
 */
import {
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { execSync } from 'child_process'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

export const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
export const BE_API = `${API_BASE}/api/v1`
export const FE_BASE = process.env.BASE_URL ?? 'http://localhost:3001'

export interface Cred {
  email: string
  password: string
  label: string
}

/** 所有者（チームを作る側）。 */
export const OWNER: Cred = {
  email: process.env.G3457_OWNER_EMAIL ?? 'e2e-pwui-1782136885@test.mannschaft.local',
  password: process.env.G3457_OWNER_PASSWORD ?? 'Passw0rd!2026',
  label: '管理者(権限あり)',
}
/** 一般メンバー（招待参加。権限なし視点）。 */
export const MEMBER: Cred = {
  email: process.env.G3457_MEMBER_EMAIL ?? 'e2e-member-1785848177@test.mannschaft.local',
  password: process.env.G3457_MEMBER_PASSWORD ?? 'Passw0rd!2026',
  label: '一般メンバー(権限なし)',
}
/** 他テナント（どのチームにも属さない利用者）。 */
export const OUTSIDER: Cred = {
  email: process.env.G3457_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local',
  password: process.env.G3457_OUTSIDER_PASSWORD ?? 'TestPass2026!',
  label: '他テナント',
}
/** SYSTEM_ADMIN。 */
export const SYSADMIN: Cred = {
  email: process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!',
  label: 'SYSTEM_ADMIN',
}

export function authHeaders(token: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}

export async function apiLogin(ctx: APIRequestContext, c: Cred): Promise<string> {
  const res = await ctx.post(`${BE_API}/auth/login`, { data: { email: c.email, password: c.password } })
  if (!res.ok()) throw new Error(`ログイン失敗(${c.email}): ${res.status()} ${await res.text()}`)
  return (await res.json()).data.accessToken as string
}

export async function apiMe(ctx: APIRequestContext, token: string): Promise<{ id: number; email: string }> {
  const res = await ctx.get(`${BE_API}/users/me`, { headers: authHeaders(token) })
  if (!res.ok()) throw new Error(`/users/me 失敗: ${res.status()}`)
  return (await res.json()).data
}

/** 使い捨てチームを作る（作成者が OWNER/ADMIN）。 */
export async function createThrowawayTeam(ctx: APIRequestContext, token: string, label: string): Promise<{ slug: string; id: number; name: string }> {
  const name = `G3457_${label}_${Date.now()}`
  const res = await ctx.post(`${BE_API}/teams`, { headers: authHeaders(token), data: { name } })
  if (!res.ok()) throw new Error(`チーム作成失敗: ${res.status()} ${await res.text()}`)
  const d = (await res.json()).data as { slug: string; id: number | string }
  // API の id は slug 文字列で返ることがある。DB の数値 ID を別に引く（scope_id・STOMP 等の数値が要る箇所用）
  const numeric = Number(dbScalar(`select id from teams where slug = '${d.slug}'`))
  return { slug: d.slug, id: numeric, name }
}

export async function deleteTeam(ctx: APIRequestContext, token: string, slug: string): Promise<void> {
  const res = await ctx.delete(`${BE_API}/teams/${slug}`, { headers: authHeaders(token) })
  if (res.status() >= 300 && res.status() !== 404) console.log(`[後始末] チーム ${slug} 削除 status=${res.status()} ${await res.text()}`)
}

/** 招待トークン(roleId=4)で MEMBER として参加させる。 */
export async function joinAsMember(
  adminCtx: APIRequestContext, adminToken: string,
  memberCtx: APIRequestContext, memberToken: string, slug: string,
): Promise<string> {
  const inv = await adminCtx.post(`${BE_API}/teams/${slug}/invite-tokens`, {
    headers: authHeaders(adminToken), data: { roleId: 4, expiresIn: '7d', maxUses: 10 },
  })
  if (!inv.ok()) throw new Error(`招待作成失敗: ${inv.status()} ${await inv.text()}`)
  const token = ((await inv.json()).data as { token: string }).token
  const join = await memberCtx.post(`${BE_API}/invite/${token}/join`, { headers: authHeaders(memberToken), data: {} })
  if (!join.ok()) throw new Error(`招待参加失敗: ${join.status()} ${await join.text()}`)
  const perm = await memberCtx.get(`${BE_API}/teams/${slug}/me/permissions`, { headers: authHeaders(memberToken) })
  return ((await perm.json()).data as { roleName: string }).roleName
}

/** 実ログイン済みの別ブラウザコンテキスト（ユーザーごとに独立セッション）を作る。 */
export async function openSession(browser: Browser, c: Cred): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL: FE_BASE, locale: 'ja-JP', timezoneId: 'Asia/Tokyo',
    viewport: { width: 1280, height: 900 },
  })
  const page = await context.newPage()
  await loginViaApi(page, { email: c.email, password: c.password }, { apiBaseUrl: API_BASE })
  return { context, page }
}

export async function gotoHydrated(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
  if (url.startsWith('/teams/')) await dismissInitialSetupDialog(page)
}

/** 新規チームの管理者に出る「メンバーの権限を初期設定」ダイアログを通常操作（あとで決める）で閉じる。 */
export async function dismissInitialSetupDialog(page: Page): Promise<void> {
  const later = page.getByRole('button', { name: 'あとで決める' })
  // ダイアログは権限取得後に遅れて出る（dev サーバでは数秒〜）。出ないページ・ロールもあるので有限待ちで判定する。
  const appeared = await later.waitFor({ state: 'visible', timeout: 15_000 }).then(() => true, () => false)
  if (appeared) {
    await later.click()
    await expect(later).toBeHidden({ timeout: 10_000 })
  }
}

/** 受信者の通知一覧画面を開き、marker を含む通知が表示されるまで再読込して待つ。 */
export async function expectNotificationOnScreen(page: Page, marker: string | RegExp, opts: { timeoutMs?: number; shot?: string } = {}): Promise<void> {
  const deadline = Date.now() + (opts.timeoutMs ?? 60_000)
  let lastErr: unknown
  while (Date.now() < deadline) {
    await gotoHydrated(page, '/notifications')
    try {
      // ノイズの多いアカウント(SYSTEM_ADMIN 等)は新着が 1 ページ目に収まらないため「もっと読む」で数ページ辿る
      for (let i = 0; i < 6; i++) {
        if (await page.getByText(marker).first().isVisible().catch(() => false)) break
        const more = page.getByRole('button', { name: 'もっと読む' })
        if (!(await more.isVisible().catch(() => false))) break
        await more.click()
        await page.waitForTimeout(1_500)
      }
      await expect(page.getByText(marker).first()).toBeVisible({ timeout: 6_000 })
      if (opts.shot) await page.screenshot({ path: `test-results/${opts.shot}.png`, fullPage: true })
      return
    } catch (e) { lastErr = e }
  }
  if (opts.shot) await page.screenshot({ path: `test-results/${opts.shot}-FAILED.png`, fullPage: true })
  throw new Error(`通知一覧に ${String(marker)} が表示されなかった: ${String(lastErr)}`)
}

/** 通知一覧に marker が出ないこと（負の視点）。少し待ってから確認する。 */
export async function expectNoNotificationOnScreen(page: Page, marker: string | RegExp, waitMs = 8_000): Promise<void> {
  await page.waitForTimeout(waitMs)
  await gotoHydrated(page, '/notifications')
  await page.waitForTimeout(2_000)
  await expect(page.getByText(marker)).toHaveCount(0)
}

/**
 * 証拠補助（読み取り専用）: dev DB の件数を数える。画面判定の代替ではなく、
 * 「新しい通知が1件増えた」ことの裏取りに使う（共有アカウントに既存通知が多く、画面の文言だけでは新旧を区別できないため）。
 */
export function dbScalar(sql: string): string {
  // wsl/docker 側の一過性失敗に備え 1 回だけ再試行する
  try { return dbScalarOnce(sql) } catch { return dbScalarOnce(sql) }
}
function dbScalarOnce(sql: string): string {
  const out = execSync(`wsl.exe -e docker exec mannschaft-mysql mysql -uroot -proot -N -B mannschaft -e "${sql.replace(/"/g, '\\"')}"`, { stdio: 'pipe' })
  return out.toString().trim()
}

export function maxNotificationId(): number {
  return Number(dbScalar('select coalesce(max(id),0) from notifications'))
}

/**
 * baseline より後に、指定ユーザー宛に作られた指定種別の通知 ID 一覧。
 * 注意: wsl.exe 経由のコマンドラインは日本語が文字化けするため、SQL には ASCII のみ使う（種別 notification_type・ASCII の語尾など）。
 */
export function notificationIdsSince(baseline: number, userId: number, type: string, opts: { scopeId?: number; titleSuffixAscii?: string } = {}): number[] {
  // cmd.exe 経由のため % を含む LIKE は使わず instr で判定する
  const suffix = opts.titleSuffixAscii ? ` and instr(title, '${opts.titleSuffixAscii.replace(/'/g, "''")}') > 0` : ''
  const scope = opts.scopeId ? ` and scope_id = ${opts.scopeId}` : ''
  const out = dbScalar(`select id from notifications where id > ${baseline} and user_id = ${userId} and notification_type = '${type}'${scope}${suffix}`)
  return out ? out.split(/\s+/).map(Number) : []
}

/** テストが作ったと確認できた通知 ID だけを削除する（ID 指定のみ）。 */
export function deleteNotificationsByIds(ids: number[]): void {
  if (ids.length === 0) return
  dbScalar(`delete from notifications where id in (${ids.join(',')})`)
  console.log(`[後始末] 通知 ${ids.length} 件を ID 指定で削除: ${ids.join(',')}`)
}

/** 後始末用: 受信者の通知を ID 指定で削除する API があれば使う（なければ何もしない）。 */
export async function newCtx(): Promise<APIRequestContext> {
  return playwrightRequest.newContext()
}
