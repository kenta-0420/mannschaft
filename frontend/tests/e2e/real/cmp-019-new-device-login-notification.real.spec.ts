/**
 * CMP-019 Wave2: 新規端末ログイン通知の実機E2E。
 *
 * 実BE + 実MySQL + 実Valkeyを対象に、ログインAPIのdeviceFingerprintを変えて
 * NEW_DEVICE_LOGINがAFTER_COMMIT後に本人だけへ届くことを確認する。
 * IPは同一localhost接続のため、X-Forwarded-Forを偽装せずfingerprint差分を主条件とする。
 *
 * 実行前提:
 *   API_BASE_URL（既定 http://localhost:8080）でBEが起動済み
 *   E2E_MYSQL_USER / E2E_MYSQL_PASSWORD と mannschaft-mysql コンテナが利用可能
 *   seed済み TEST_MEMBER_EMAIL（既定 e2e-user）と TEST_OTHER_EMAIL（既定 e2e-admin）
 *   両ユーザーのパスワードは TEST_*_PASSWORD（既定 TestPass2026!）
 */
import { test, expect, type APIRequestContext } from '@playwright/test'
import { execSync } from 'node:child_process'

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const MEMBER = {
  email: process.env.TEST_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_MEMBER_PASSWORD ?? 'TestPass2026!',
}
const OTHER = {
  email: process.env.TEST_OTHER_EMAIL ?? 'e2e-admin@test.mannschaft.local',
  password: process.env.TEST_OTHER_PASSWORD ?? 'TestPass2026!',
}
const MYSQL_USER = process.env.E2E_MYSQL_USER ?? ''
const MYSQL_PASSWORD = process.env.E2E_MYSQL_PASSWORD ?? ''
const VALKEY_CONTAINER = process.env.E2E_VALKEY_CONTAINER ?? 'mannschaft-valkey'
const RUN_TAG = `CMP019_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
const KNOWN_FINGERPRINT = `cmp019-known-${RUN_TAG}`
const NEW_FINGERPRINT = `cmp019-new-${RUN_TAG}`
const OTHER_FINGERPRINT = `cmp019-other-${RUN_TAG}`

type Notification = {
  id: number
  userId?: number
  notificationType?: string
  title?: string
  body?: string
  actionUrl?: string
}

function mysql(statement: string): void {
  if (!MYSQL_USER || !MYSQL_PASSWORD) throw new Error('E2E_MYSQL_USER/E2E_MYSQL_PASSWORD が必要です')
  const escaped = statement.replace(/"/g, '\\"')
  const command = `docker exec mannschaft-mysql mysql -u${MYSQL_USER} -p${MYSQL_PASSWORD} mannschaft -e "${escaped}"`
  execSync(process.platform === 'win32' ? `wsl.exe -e ${command}` : command, { stdio: 'pipe' })
}

function deleteNewDeviceRateKey(userId: number): void {
  const windowStart = Math.floor(Date.now() / 1000 / 3600) * 3600
  const key = `mannschaft:rate:auth:new-device-login:u:${userId}:${windowStart}`
  const command = `docker exec ${VALKEY_CONTAINER} redis-cli DEL ${key}`
  execSync(process.platform === 'win32' ? `wsl.exe -e ${command}` : command, { stdio: 'pipe' })
}

async function login(
  request: APIRequestContext,
  credentials: typeof MEMBER,
  deviceFingerprint?: string,
): Promise<{ token: string; userId: number }> {
  const response = await request.post(`${BE}/api/v1/auth/login`, {
    data: { ...credentials, ...(deviceFingerprint ? { deviceFingerprint } : {}) },
    headers: { 'Content-Type': 'application/json' },
  })
  expect(response.status(), `${credentials.email} のログイン`).toBe(200)
  const token = (await response.json())?.data?.accessToken
  expect(token, `${credentials.email} のaccessToken`).toBeTruthy()
  const me = await request.get(`${BE}/api/v1/users/me`, { headers: { Authorization: `Bearer ${token}` } })
  expect(me.status(), `${credentials.email} の本人情報`).toBe(200)
  return { token: token as string, userId: (await me.json())?.data?.id as number }
}

async function notifications(request: APIRequestContext, token: string): Promise<Notification[]> {
  const response = await request.get(`${BE}/api/v1/notifications?page=0&size=100`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status(), '本人の通知一覧').toBe(200)
  return ((await response.json())?.data ?? []) as Notification[]
}

function newDeviceNotifications(rows: Notification[]): Notification[] {
  return rows.filter((row) => row.notificationType === 'NEW_DEVICE_LOGIN')
}

async function settleNotifications(request: APIRequestContext, token: string): Promise<Notification[]> {
  let previous = ''
  let stable = 0
  let latest: Notification[] = []
  await expect.poll(async () => {
    latest = await notifications(request, token)
    const signature = latest.map((row) => row.id).sort((a, b) => a - b).join(',')
    stable = signature === previous ? stable + 1 : 0
    previous = signature
    return stable
  }, { timeout: 15_000, intervals: [500, 1_000, 2_000] }).toBeGreaterThanOrEqual(2)
  return latest
}

test.describe('CMP-019 Wave2: 新規端末ログイン通知', () => {
  test.describe.configure({ mode: 'serial' })
  test.use({ storageState: { cookies: [], origins: [] } })
  test.setTimeout(180_000)

  test('本人通知・認可境界・重複抑止・誤PW非発火を実DBで確認する', async ({ request }) => {
    test.skip(!MYSQL_USER || !MYSQL_PASSWORD || !VALKEY_CONTAINER, 'MySQL/Valkey cleanup用の環境変数が未設定')

    // userId解決用のseedログイン後、対象ユーザーの現行1時間バケットだけを消す。
    // これにより再実行時も、このテスト専用の通知発火がレート制限に埋もれない。
    const memberSession = await login(request, MEMBER)
    deleteNewDeviceRateKey(memberSession.userId)
    // 初回ログインが既存seedセッションに対して発火しても、非同期通知が収束してからbaseline化する。
    const memberBefore = await login(request, MEMBER, KNOWN_FINGERPRINT)
    const otherSession = await login(request, OTHER, OTHER_FINGERPRINT)
    const memberBaseline = await settleNotifications(request, memberBefore.token)
    const otherBaseline = await settleNotifications(request, otherSession.token)
    const memberBaselineIds = new Set(memberBaseline.map((row) => row.id))
    const otherBaselineIds = new Set(otherBaseline.map((row) => row.id))

    try {
      // 既存端末セッションを確実に作った後、別context相当の新fingerprintでログインする。
      const newDeviceSession = await login(request, MEMBER, NEW_FINGERPRINT)
      let hit: Notification | undefined
      await expect.poll(async () => {
        const rows = await notifications(request, newDeviceSession.token)
        hit = newDeviceNotifications(rows).find((row) => !memberBaselineIds.has(row.id))
        return hit
      }, { timeout: 60_000, intervals: [500, 1_000, 2_000] }).toBeTruthy()

      expect(hit?.actionUrl, '新規端末通知のアクション先').toBe('/account/sessions')
      expect(hit?.body ?? '', '通知本文にIPを含めない').not.toMatch(/(?:\d{1,3}\.){3}\d{1,3}|::1|0:0:0:0/)
      expect(hit?.body ?? '', '通知本文にテストfingerprintを含めない').not.toContain(NEW_FINGERPRINT)

      // 別ユーザーの一覧に、今回生成された本人通知IDが混入しないこと。
      const otherAfter = await notifications(request, otherSession.token)
      expect(newDeviceNotifications(otherAfter).filter((row) => !otherBaselineIds.has(row.id))).toHaveLength(0)

      // 既知fingerprintでの再ログインは新規端末通知を増やさない。
      const beforeKnownRetry = await notifications(request, memberBefore.token)
      const beforeKnownCount = newDeviceNotifications(beforeKnownRetry).length
      await login(request, MEMBER, NEW_FINGERPRINT)
      await new Promise((resolve) => setTimeout(resolve, 2_000))
      const afterKnownRetry = await notifications(request, memberBefore.token)
      expect(newDeviceNotifications(afterKnownRetry)).toHaveLength(beforeKnownCount)

      // 誤パスワードではログイン失敗し、通知も増えない。
      const wrong = await request.post(`${BE}/api/v1/auth/login`, {
        data: { ...MEMBER, password: `${MEMBER.password}-wrong`, deviceFingerprint: `cmp019-wrong-${RUN_TAG}` },
      })
      expect(wrong.ok()).toBe(false)
      await new Promise((resolve) => setTimeout(resolve, 1_000))
      const afterWrongPassword = await notifications(request, memberBefore.token)
      expect(newDeviceNotifications(afterWrongPassword)).toHaveLength(beforeKnownCount)
    } finally {
      // 通知は削除APIを持たないため、今回のfingerprintで作ったrefresh tokenと、
      // baselineに無いNEW_DEVICE_LOGINだけをID指定で消し、既存データを保持する。
      const rows = await notifications(request, memberBefore.token)
      const generatedIds = newDeviceNotifications(rows)
        .filter((row) => !memberBaselineIds.has(row.id))
        .map((row) => row.id)
      const idClause = generatedIds.length > 0 ? ` AND id IN (${generatedIds.join(',')})` : ' AND 1=0'
      mysql(`DELETE FROM notifications WHERE notification_type='NEW_DEVICE_LOGIN'${idClause}; DELETE FROM refresh_tokens WHERE device_fingerprint IN ('${KNOWN_FINGERPRINT}','${NEW_FINGERPRINT}','${OTHER_FINGERPRINT}');`)
      deleteNewDeviceRateKey(memberSession.userId)
    }
  })
})
