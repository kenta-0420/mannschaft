import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { expect, type Browser, type BrowserContext, type Page, type Response } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'

/**
 * F01.2.1（組織がチームをグループ化）実機E2E の共通土台。
 *
 * - API を使うのは「ログイン・前提データの作成・後始末・観測（DB/通知/ジョブ表）」だけ。
 *   受け入れ条件の対象操作は spec 側で必ず画面のクリック・入力・送信で行う。
 * - 利用者は毎回新規登録する（seed の認証情報ドリフトを避ける）。メール認証トークンは
 *   SHA-256 で保存され平文を復元できないため、有効化だけは users.status の直更新で行う。
 * - 検証用 BE（既定 8081）と検証用 FE（既定 3001）を前提にする。本陣 8080/3000 は使わない。
 */

export const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
export const FE = process.env.BASE_URL ?? 'http://localhost:3001'
export const PASSWORD = 'Passw0rd!2026'
const SYSTEM_ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const SYSTEM_ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

/**
 * 実行ごとに一意な接尾辞（slug・メールアドレス・名前に付ける）。失敗でワーカーが再起動しても
 * 同じ実行（同じ親プロセス）の間は同じ値を使う（名前で前提データを引き直すため）。
 */
export const RUN = (() => {
  const file = path.resolve('test-results', `f0121-run-${process.ppid}.txt`)
  if (existsSync(file)) return readFileSync(file, 'utf8').trim()
  const value = Date.now().toString(36)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, value)
  return value
})()

/** 共有開発 DB に SQL を流す（WSL2 の docker 経由）。結果はタブ区切り・ヘッダなし。 */
export function sql(statement: string): string {
  const args = [
    'exec',
    '-i',
    'mannschaft-mysql',
    'mysql',
    '-uroot',
    '-proot',
    '--default-character-set=utf8mb4',
    '-N',
    '-B',
    'mannschaft',
  ]
  const file = process.platform === 'win32' ? 'wsl.exe' : 'docker'
  const fullArgs = process.platform === 'win32' ? ['-e', 'docker', ...args] : args
  return execFileSync(file, fullArgs, {
    input: statement,
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
    timeout: 60_000,
  }).trim()
}

/**
 * WSL2 mirrored 越しの接続リセット（ECONNRESET 等。HTTP 応答が返る前の通信断）だけを再試行する fetch。
 * HTTP ステータスの付いた応答（4xx/5xx を含む）は再試行せずそのまま返す（製品の応答を隠さない）。
 */
async function netFetch(url: string, init?: RequestInit): Promise<globalThis.Response> {
  let lastError: unknown
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      return await fetch(url, init)
    } catch (error) {
      lastError = error
      await new Promise((resolve) => setTimeout(resolve, 1_000 * (attempt + 1)))
    }
  }
  throw lastError
}

export interface Actor {
  label: string
  email: string
  password: string
  id: number
  nickname: string
}

/**
 * 実 BE の応答本文。観測対象（設定・申請・通知・エラー本文など）ごとに形が異なり、spec 側でキーを直接読んで
 * 期待値と突き合わせるため、ここだけは型を固定しない（検証用ヘルパーに限った例外）。
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- 観測する応答本文の形が API ごとに異なるため
export type ApiBody = any

export interface ApiResult<T = ApiBody> {
  status: number
  body: T
}

const tokenCache = new Map<string, string>()

async function bearer(actor: Pick<Actor, 'email' | 'password'>, fresh = false): Promise<string> {
  const cached = tokenCache.get(actor.email)
  if (cached && !fresh) return cached
  const response = await netFetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: actor.email, password: actor.password }),
  })
  if (!response.ok) {
    throw new Error(`API ログイン失敗 ${actor.email}: ${response.status} ${await response.text()}`)
  }
  const token = (await response.json()).data.accessToken as string
  tokenCache.set(actor.email, token)
  return token
}

/** 利用者として API を呼ぶ（前提作成・後始末・観測に限って使う）。 */
export async function apiAs<T = ApiBody>(
  actor: Pick<Actor, 'email' | 'password'> | null,
  method: string,
  path: string,
  body?: unknown,
): Promise<ApiResult<T>> {
  const call = async (fresh: boolean) => {
    const headers: Record<string, string> = { 'content-type': 'application/json' }
    if (actor) headers.authorization = `Bearer ${await bearer(actor, fresh)}`
    return netFetch(`${API}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }
  let response = await call(false)
  if (response.status === 401 && actor) response = await call(true)
  const text = await response.text()
  let parsed: ApiBody = text
  try {
    parsed = text ? JSON.parse(text) : null
  } catch {
    parsed = text
  }
  return { status: response.status, body: parsed as T }
}

/** 前提作成の API 呼び出し。期待外のステータスなら即座に失敗させる（握りつぶさない）。 */
export async function must<T = ApiBody>(
  actor: Pick<Actor, 'email' | 'password'> | null,
  method: string,
  path: string,
  body?: unknown,
  expected: number[] = [200, 201, 204],
): Promise<T> {
  const result = await apiAs<T>(actor, method, path, body)
  if (!expected.includes(result.status)) {
    throw new Error(`${method} ${path} -> ${result.status} ${JSON.stringify(result.body)}`)
  }
  return result.body
}

export const systemAdmin: Actor = {
  label: 'SA',
  email: SYSTEM_ADMIN_EMAIL,
  password: SYSTEM_ADMIN_PASSWORD,
  id: 0,
  nickname: 'e2e-admin',
}

/** 共有 Valkey 上のキーを消す（前提作成用。登録のレート制限カウンタのリセットに使う）。 */
export function valkeyDel(pattern: string): void {
  const script = `for k in $(valkey-cli --scan --pattern '${pattern}'); do valkey-cli DEL "$k" >/dev/null; done`
  const args = ['exec', 'mannschaft-valkey', 'sh', '-c', script]
  if (process.platform === 'win32') execFileSync('wsl.exe', ['-e', 'docker', ...args], { stdio: 'pipe', timeout: 60_000 })
  else execFileSync('docker', args, { stdio: 'pipe', timeout: 60_000 })
}

/**
 * 検証用利用者の置き場（実行をまたいで再利用する）。登録 API は 1 IP あたり 10 件/時の制限があるため、
 * 同じ役割名の利用者は一度だけ登録し、以後は使い回す。所属は毎回新しく作る組織・チームで決まるので、
 * 使い回しても固定人物の意味は変わらない（N は一度もどこにも所属させない）。
 */
const POOL_FILE = path.resolve('tests/e2e/.auth', 'f0121-actor-pool.json')

function readPool(): Record<string, Actor> {
  if (!existsSync(POOL_FILE)) return {}
  return JSON.parse(readFileSync(POOL_FILE, 'utf8')) as Record<string, Actor>
}

/** 利用者を用意する（置き場にあれば再利用、無ければ登録して有効化）。 */
export async function createActor(label: string): Promise<Actor> {
  // 既定は毎回新規登録（実行をまたいだ所属・通知の持ち越しで観測が濁らないように）。
  // F0121_REUSE_ACTORS=1 のときだけ置き場を再利用する。
  const pool = process.env.F0121_REUSE_ACTORS === '1' ? readPool() : {}
  const pooled = pool[label]
  if (pooled) {
    const alive = await netFetch(`${API}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ email: pooled.email, password: pooled.password }),
    })
    if (alive.ok) {
      tokenCache.set(pooled.email, (await alive.json()).data.accessToken)
      return pooled
    }
  }
  const slug = label.toLowerCase().replace(/[^a-z0-9]/g, '')
  const email = `e2e-og-${slug}-${RUN}@test.mannschaft.local`
  const nickname = `og${slug}${RUN}`
  valkeyDel('mannschaft:auth:register_attempt:*')
  const register = await netFetch(`${API}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      email,
      password: PASSWORD,
      lastName: '検証',
      firstName: label,
      nickname,
      birth_date: '1990-01-01',
      privacyPolicyAccepted: true,
      privacyPolicyVersion: '1.1.0',
      postalCode: '1000001',
      timezone: 'Asia/Tokyo',
      locale: 'ja',
    }),
  })
  if (register.status !== 201) {
    throw new Error(`利用者登録失敗 ${label}: ${register.status} ${await register.text()}`)
  }
  sql(
    `UPDATE users SET status='ACTIVE' WHERE email='${email}' AND display_name='${nickname}' AND status='PENDING_VERIFICATION';`,
  )
  const id = Number(sql(`SELECT id FROM users WHERE email='${email}';`))
  const actor = { label, email, password: PASSWORD, id, nickname }
  await bearer(actor, true)
  const next = readPool()
  next[label] = actor
  mkdirSync(path.dirname(POOL_FILE), { recursive: true })
  writeFileSync(POOL_FILE, JSON.stringify(next, null, 2))
  return actor
}

export interface Scope {
  slug: string
  numericId: number
  name: string
}

/**
 * 新規スコープで管理者に出る「メンバーの権限を初期設定」案内を、既定値のまま保存して出なくする（前提作成）。
 * 案内は既定値（inherited）のときだけ出て、表示が遅れて対象操作のクリックを塞ぐため。値は変えない。
 */
async function settleMemberPermissions(owner: Actor, scopeType: 'ORGANIZATION' | 'TEAM', scopeId: number) {
  const query = `scopeType=${scopeType}&scopeId=${scopeId}`
  const current = await must(owner, 'GET', `/api/v1/admin/member-permissions?${query}`)
  const permissions = (current.data.permissions as Array<{ name: string; enabled: boolean }>).map(({ name, enabled }) => ({
    name,
    enabled,
  }))
  await must(owner, 'PUT', `/api/v1/admin/member-permissions?${query}`, { permissions })
}

export async function createOrg(owner: Actor, name: string, visibility = 'PUBLIC'): Promise<Scope> {
  const body = await must(owner, 'POST', '/api/v1/organizations', {
    name,
    visibility,
    orgType: 'ASSOCIATION',
  })
  await settleMemberPermissions(owner, 'ORGANIZATION', body.data.numericId)
  return { slug: body.data.slug, numericId: body.data.numericId, name }
}

export async function createTeam(owner: Actor, name: string, visibility = 'PUBLIC'): Promise<Scope> {
  const body = await must(owner, 'POST', '/api/v1/teams', { name, visibility })
  await settleMemberPermissions(owner, 'TEAM', body.data.numericId)
  return { slug: body.data.slug, numericId: body.data.numericId, name }
}

const ROLE_ID = { ADMIN: 2, DEPUTY_ADMIN: 3, MEMBER: 4, SUPPORTER: 5 } as const

/** 招待トークン経由で所属させ、必要なら ADMIN がロールを変える（前提作成）。 */
export async function joinScope(
  kind: 'organizations' | 'teams',
  admin: Actor,
  scopeSlug: string,
  member: Actor,
  role: keyof typeof ROLE_ID,
): Promise<void> {
  const joinRole = role === 'SUPPORTER' ? ROLE_ID.SUPPORTER : ROLE_ID.MEMBER
  const token = await must(admin, 'POST', `/api/v1/${kind}/${scopeSlug}/invite-tokens`, {
    roleId: joinRole,
  })
  await must(member, 'POST', `/api/v1/invite/${token.data.token}/join`)
  if (role === 'DEPUTY_ADMIN' || role === 'ADMIN') {
    await must(admin, 'PATCH', `/api/v1/${kind}/${scopeSlug}/members/${member.id}/role`, {
      roleId: ROLE_ID[role],
    })
  }
}

/** 組織X → チームT の招待を作ってチーム側で承諾し、ACTIVE 加盟にする（前提作成）。 */
export async function affiliate(
  orgAdmin: Actor,
  orgSlug: string,
  teamAdmin: Actor,
  teamSlug: string,
  groupId?: string,
): Promise<void> {
  await must(orgAdmin, 'POST', `/api/v1/organizations/${orgSlug}/team-invites`, {
    teamSlug,
    ...(groupId ? { groupId } : {}),
  })
  const invites = await must(teamAdmin, 'GET', `/api/v1/teams/${teamSlug}/org-invites`)
  const invite = (invites.data as Array<{ id: string; organization?: { slug: string } }>).find(
    (row) => row.organization?.slug === orgSlug,
  )
  if (!invite) throw new Error(`招待が見つからない ${orgSlug} -> ${teamSlug}`)
  await must(teamAdmin, 'POST', `/api/v1/teams/${teamSlug}/org-invites/${invite.id}/accept`)
}

export async function putSettings(
  orgAdmin: Actor,
  orgSlug: string,
  settings: {
    teamApplicationEnabled: boolean
    teamGroupsEnabled: boolean
    applicationGroupMode: 'OFF' | 'OPTIONAL' | 'REQUIRED'
    applicationGuidance?: string | null
  },
): Promise<void> {
  await must(orgAdmin, 'PUT', `/api/v1/organizations/${orgSlug}/team-affiliation-settings`, settings)
}

export async function createGroup(orgAdmin: Actor, orgSlug: string, name: string): Promise<string> {
  const body = await must(orgAdmin, 'POST', `/api/v1/organizations/${orgSlug}/team-groups`, { name })
  return body.data.id as string
}

/** 利用者の通知一覧（観測用）。 */
export async function notificationsOf(
  actor: Actor,
): Promise<Array<{ id: number; notificationType?: string; type?: string; title?: string; actionUrl?: string }>> {
  const body = await must(actor, 'GET', '/api/v1/notifications?page=0&size=50')
  return (body.data ?? []) as Array<{
    id: number
    notificationType?: string
    type?: string
    title?: string
    actionUrl?: string
  }>
}

export function typeOf(notification: { notificationType?: string; type?: string }): string | undefined {
  return notification.notificationType ?? notification.type
}

/** fan-out ワーカーは5秒間隔。push の到達・不到達の観測前に置く待ち時間（設計書 §16.0）。 */
export const FANOUT_WAIT_MS = 12_000

/** 新しいブラウザ文脈で利用者としてログインし、指定 URL を開く（1文脈=1ログイン）。 */
export async function openAs(
  browser: Browser,
  actor: Actor | null,
  path: string,
  viewport = { width: 1280, height: 900 },
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    baseURL: FE,
    storageState: { cookies: [], origins: [] },
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport,
  })
  const page = await context.newPage()
  if (actor) {
    await page.goto(`${FE}/robots.txt`)
    await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  }
  await page.goto(`${FE}${path}`, { waitUntil: 'commit' })
  // ミドルウェアの追い返しと再読込が競合しないよう、再読込せずに水和を待つ。
  // 共有開発機が高負荷のとき dev サーバーの初回変換が 75 秒を超えるため、待ち時間を長めに取る。
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 180_000 },
  )
  return { context, page }
}

/** 新規組織で初回に出る「初期権限の案内」を閉じる（権限は変更しない）。 */
export async function dismissPermissionSetup(page: Page): Promise<void> {
  const dialog = page.getByTestId('member-permission-setup')
  const shown = await dialog
    .waitFor({ state: 'visible', timeout: 3_000 })
    .then(
      () => true,
      () => false,
    )
  if (shown) {
    await dialog.getByRole('button', { name: 'あとで決める' }).click()
    await expect(dialog).toBeHidden()
  }
}

/** 管理レンズを ON にする（組織・チームの管理系 UI は管理レンズ ON のときだけ出る）。 */
export async function enableAdminLens(page: Page): Promise<void> {
  const lens = page.getByTestId('scope-lens-admin')
  await expect(lens).toBeVisible({ timeout: 30_000 })
  if ((await lens.getAttribute('aria-pressed')) !== 'true') await lens.click()
}

export function isApi(response: Response, method: string, pathname: string | RegExp): boolean {
  if (response.request().method() !== method) return false
  const path = new URL(response.url()).pathname
  return typeof pathname === 'string' ? path === pathname : pathname.test(path)
}

/** 画面の操作で発生する API 応答を待ちながらクリックする。 */
export async function clickAndWait(
  page: Page,
  click: () => Promise<void>,
  method: string,
  pathname: string | RegExp,
): Promise<Response> {
  const [response] = await Promise.all([
    page.waitForResponse((r) => isApi(r, method, pathname), { timeout: 30_000 }),
    click(),
  ])
  return response
}

/** バッチの手動起動（SYSTEM_ADMIN。画面が無いため API。用途は「時間を進める」準備）。 */
export async function triggerBatch(name: string): Promise<ApiResult> {
  return apiAs(systemAdmin, 'POST', `/api/v1/system-admin/batch/${name}/trigger?sync=true`)
}

/** 後始末: 作成した組織・チームをアーカイブ（論理削除）する。失敗は結果として返す（隠さない）。 */
export async function archiveScope(
  kind: 'organizations' | 'teams',
  owner: Actor,
  slug: string,
): Promise<number> {
  const result = await apiAs(owner, 'DELETE', `/api/v1/${kind}/${slug}`)
  return result.status
}
