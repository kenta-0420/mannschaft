import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { expect, type Browser, type BrowserContext, type Page, type Response } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'

/**
 * CMP-261103-1225 Phase1（N+1 解消）実機E2E の共通土台。
 *
 * - API を使うのは「ログイン・前提データの作成・後始末」だけ。確認対象の表示は spec 側で必ず画面を開いて確かめる。
 * - 利用者は毎回新規登録する。メール認証トークンは SHA-256 で保存され平文を復元できないため、
 *   有効化だけは users.status の直更新で行う（f0121-org-groups-world.ts と同じ流儀。あちらは専用設定の印が無いと
 *   読み込み時に throw するため、必要な関数だけをここへ写している）。
 * - 既定の接続先は検証用 BE 8081 / 検証用 FE 3017（3001 は他セッションの検証 FE が使用中だったため）。
 */
export const API = process.env.API_BASE_URL || 'http://localhost:8081'
export const FE = process.env.BASE_URL || 'http://localhost:3017'
export const PASSWORD = 'Passw0rd!2026'
export const RUN = Date.now().toString(36)

/** 共有開発 DB に SQL を流す（WSL2 の docker 経由）。結果はタブ区切り・ヘッダなし。 */
export function sql(statement: string): string {
  const args = ['exec', '-i', 'mannschaft-mysql', 'mysql', '-uroot', '-proot', '--default-character-set=utf8mb4', '-N', '-B', 'mannschaft']
  const file = process.platform === 'win32' ? 'wsl.exe' : 'docker'
  const fullArgs = process.platform === 'win32' ? ['-e', 'docker', ...args] : args
  return execFileSync(file, fullArgs, { input: statement, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], timeout: 60_000 }).trim()
}

function valkeyDel(key: string): void {
  const args = ['exec', 'mannschaft-valkey', 'valkey-cli', 'DEL', key]
  if (process.platform === 'win32') execFileSync('wsl.exe', ['-e', 'docker', ...args], { stdio: 'pipe', timeout: 60_000 })
  else execFileSync('docker', args, { stdio: 'pipe', timeout: 60_000 })
}

/** WSL2 mirrored 越しの接続断（HTTP 応答が返る前の通信断）だけを再試行する。HTTP 応答はそのまま返す。 */
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

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- 前提作成で読む応答本文の形が API ごとに異なるため
export type ApiBody = any

const tokenCache = new Map<string, string>()

async function bearer(actor: Pick<Actor, 'email' | 'password'>, fresh = false): Promise<string> {
  const cached = tokenCache.get(actor.email)
  if (cached && !fresh) return cached
  const response = await netFetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: actor.email, password: actor.password }),
  })
  if (!response.ok) throw new Error(`API ログイン失敗 ${actor.email}: ${response.status} ${await response.text()}`)
  const token = (await response.json()).data.accessToken as string
  tokenCache.set(actor.email, token)
  return token
}

export async function apiAs(
  actor: Pick<Actor, 'email' | 'password'> | null,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ status: number; body: ApiBody }> {
  const call = async (fresh: boolean) => {
    const headers: Record<string, string> = { 'content-type': 'application/json' }
    if (actor) headers.authorization = `Bearer ${await bearer(actor, fresh)}`
    return netFetch(`${API}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
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
  return { status: response.status, body: parsed }
}

/** 前提作成の API 呼び出し。期待外のステータスなら即座に失敗させる（握りつぶさない）。 */
export async function must(
  actor: Pick<Actor, 'email' | 'password'> | null,
  method: string,
  path: string,
  body?: unknown,
  expected: number[] = [200, 201, 204],
): Promise<ApiBody> {
  const result = await apiAs(actor, method, path, body)
  if (!expected.includes(result.status)) throw new Error(`${method} ${path} -> ${result.status} ${JSON.stringify(result.body)}`)
  return result.body
}

export async function createActor(label: string): Promise<Actor> {
  const slug = label.toLowerCase().replace(/[^a-z0-9]/g, '')
  const email = `e2e-n1p1-${slug}-${RUN}@test.mannschaft.local`
  const nickname = `n1p1${slug}${RUN}`
  valkeyDel('mannschaft:auth:register_attempt:127.0.0.1')
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
  if (register.status !== 201) throw new Error(`利用者登録失敗 ${label}: ${register.status} ${await register.text()}`)
  sql(`UPDATE users SET status='ACTIVE' WHERE email='${email}' AND display_name='${nickname}' AND status='PENDING_VERIFICATION';`)
  const id = Number(sql(`SELECT id FROM users WHERE email='${email}';`))
  const actor = { label, email, password: PASSWORD, id, nickname }
  await bearer(actor, true)
  return actor
}

/**
 * 作成した前提データの記録（後始末用）。1 行 1 JSON で追記し、globalTeardown（n1p1-global-teardown.ts）が
 * 実行の最後に、テストの成否や中断にかかわらず削除する（中断で後始末が走らなかった分も次回の実行で拾う）。
 */
// test-results/ は Playwright が実行開始時に消すため置かない。tests/e2e/.auth/*.json は .gitignore 済み。
export const ASSET_FILE = path.resolve('tests', 'e2e', '.auth', 'n1p1-assets.json')
export interface AssetRecord {
  label: string
  path: string
  owner: { email: string; password: string }
}
export function readAssets(): AssetRecord[] {
  return existsSync(ASSET_FILE) ? (JSON.parse(readFileSync(ASSET_FILE, 'utf8')) as AssetRecord[]) : []
}
export function writeAssets(records: AssetRecord[]): void {
  mkdirSync(path.dirname(ASSET_FILE), { recursive: true })
  writeFileSync(ASSET_FILE, JSON.stringify(records, null, 2))
}
export function recordAsset(label: string, deletePath: string, owner: Actor): void {
  writeAssets([...readAssets(), { label, path: deletePath, owner: { email: owner.email, password: owner.password } }])
}

export interface Scope {
  slug: string
  numericId: number
  name: string
}

/** 新規スコープで出る「メンバーの権限を初期設定」案内を、既定値のまま保存して出なくする（値は変えない）。 */
async function settleMemberPermissions(owner: Actor, scopeType: 'ORGANIZATION' | 'TEAM', scopeId: number) {
  const query = `scopeType=${scopeType}&scopeId=${scopeId}`
  const current = await must(owner, 'GET', `/api/v1/admin/member-permissions?${query}`)
  const permissions = (current.data.permissions as Array<{ name: string; enabled: boolean }>).map(({ name, enabled }) => ({ name, enabled }))
  await must(owner, 'PUT', `/api/v1/admin/member-permissions?${query}`, { permissions })
}

function uniqueSlug(prefix: string): string {
  return `${prefix}-${RUN}-${Math.random().toString(36).slice(2, 7)}`
}

export async function createOrg(owner: Actor, name: string, visibility = 'PUBLIC'): Promise<Scope> {
  const body = await must(owner, 'POST', '/api/v1/organizations', { slug: uniqueSlug('n1o'), name, visibility, orgType: 'ASSOCIATION' })
  recordAsset(`org ${body.data.slug}`, `/api/v1/organizations/${body.data.slug}`, owner)
  await settleMemberPermissions(owner, 'ORGANIZATION', body.data.numericId)
  return { slug: body.data.slug, numericId: body.data.numericId, name }
}

export async function createTeam(owner: Actor, name: string, visibility = 'PUBLIC'): Promise<Scope> {
  const body = await must(owner, 'POST', '/api/v1/teams', { slug: uniqueSlug('n1t'), name, visibility })
  recordAsset(`team ${body.data.slug}`, `/api/v1/teams/${body.data.slug}`, owner)
  await settleMemberPermissions(owner, 'TEAM', body.data.numericId)
  return { slug: body.data.slug, numericId: body.data.numericId, name }
}

export const ROLE_ID = { ADMIN: 2, DEPUTY_ADMIN: 3, MEMBER: 4, SUPPORTER: 5 } as const

/** 招待トークン経由で MEMBER として所属させる（前提作成）。 */
export async function joinAsMember(kind: 'organizations' | 'teams', admin: Actor, scopeSlug: string, member: Actor): Promise<void> {
  const token = await must(admin, 'POST', `/api/v1/${kind}/${scopeSlug}/invite-tokens`, { roleId: ROLE_ID.MEMBER })
  await must(member, 'POST', `/api/v1/invite/${token.data.token}/join`)
}

/** 新しいブラウザ文脈で利用者としてログインし、指定 URL を開く（1文脈=1ログイン）。 */
export async function openAs(browser: Browser, actor: Actor, path?: string): Promise<{ context: BrowserContext; page: Page; apiOrigins: Set<string> }> {
  const context = await browser.newContext({
    baseURL: FE,
    storageState: { cookies: [], origins: [] },
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    viewport: { width: 1280, height: 900 },
  })
  const page = await context.newPage()
  const apiOrigins = new Set<string>()
  page.on('request', (request) => {
    if (!request.url().includes('/api/v1/')) return
    const url = new URL(request.url())
    // CSP 違反レポートは FE 自身（Nitro）の受け口へ送られる仕様なので API の向き先判定から外し、内容をログに残す
    if (url.pathname === '/api/v1/security/csp-reports') {
      console.log(`[n1p1] CSP 違反レポート送信 page=${page.url()} body=${(request.postData() ?? '').slice(0, 400)}`)
      return
    }
    // 検証 BE 以外へ出た API 呼び出しは、どのパスかも残す
    apiOrigins.add(url.origin === new URL(API).origin ? url.origin : `${url.origin}${url.pathname}`)
  })
  await page.goto(`${FE}/robots.txt`)
  await loginViaApi(page, actor, { apiBaseUrl: API, deferNavigation: true })
  // path を省略すると robots.txt のまま返す（呼び出し側で応答待ちと同時に遷移させるため）
  if (path) await gotoAndHydrate(page, path)
  return { context, page, apiOrigins }
}

export async function gotoAndHydrate(page: Page, path: string): Promise<void> {
  await page.goto(`${FE}${path}`, { waitUntil: 'commit' })
  await page.waitForFunction(
    () => {
      const el = document.querySelector('#__nuxt')
      return el !== null && '__vue_app__' in el && el.childElementCount > 0
    },
    undefined,
    { timeout: 180_000 },
  )
}

/**
 * 指定 API の応答を待つ Promise を「今」登録して返す（遷移・クリックより前に呼ぶこと）。
 * 負の視点では、この応答（403/404 等）を認可判定が終わった証拠として受け取ってから非表示を確かめる。
 */
export function responseOf(page: Page, pathname: RegExp, method = 'GET'): Promise<Response> {
  return page.waitForResponse((r) => r.request().method() === method && pathname.test(new URL(r.url()).pathname), { timeout: 120_000 })
}

/** 指定 API の応答を待ちつつ操作する（画面が実 BE を叩いたことの証拠を取る）。 */
export async function waitApi(page: Page, pathname: RegExp, action: () => Promise<unknown>): Promise<number> {
  const [response] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'GET' && pathname.test(new URL(r.url()).pathname), { timeout: 90_000 }),
    action(),
  ])
  return response.status()
}

export { expect }
