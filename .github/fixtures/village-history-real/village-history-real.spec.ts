// 配置候補: frontend/tests/e2e/real/village-history-real.spec.ts。未実行。
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

// login Cookie/bodyをraw trace/HAR/videoへ保存しない。業務画面の安全なPNG/DOMは別回収。
test.use({ trace: 'off', video: 'off' })

type ActorFixture = {
  userId: number
  roleEvidence: { villageId: string; membershipId: string; role: string; leftAt: string | null }
  credentialEnvPrefix: string
  expectedIds: string[] // setup後の実DB created_at DESC,id DESCによる全件順序
  representativeRows: {
    id: string; status: 'APPROVED' | 'WITHDRAWN' | 'REJECTED' | 'PENDING'; subjectType: 'USER'
    message: string; reviewComment: string | null
  }[]
  leftVillageRequestId?: string
}
type Fixture = {
  approvedOwnedFreshFixture: true
  actors: [ActorFixture, ActorFixture, ActorFixture]
}

function requiredEnv(key: string): string {
  const value = process.env[key]
  if (!value) throw new Error(`必要な環境キーが未設定: ${key}`)
  return value
}

let evidenceOrdinal = 0
type SafePhase = 'INIT' | 'CONTEXT_WAIT' | 'CONTEXT_DONE' | 'PAGE_WAIT' | 'PAGE_DONE'
  | 'LOGIN_WAIT' | 'LOGIN_RETURNED' | 'ME_WAIT' | 'ME_RESPONSE' | 'ME_BODY_WAIT' | 'ME_BODY_DONE'
  | 'ME_VERIFIED' | 'NAVIGATION_WAIT' | 'NAVIGATION_DONE' | 'HISTORY_ACTION_WAIT' | 'HISTORY_ACTION_DONE'
  | 'HISTORY_RESPONSE_WAIT' | 'HISTORY_RESPONSE_DONE' | 'HISTORY_BODY_WAIT' | 'HISTORY_BODY_DONE'
  | 'HISTORY_ASSERT_WAIT' | 'HISTORY_ASSERT_DONE' | 'CAPTURE_WAIT' | 'CAPTURE_DONE' | 'UI_COMPLETE'
const progress = {
  phase: 'INIT' as SafePhase, actorIndex: null as number | null,
  canonicalLoginSuccesses: 0, meStatus: null as number | null,
  uiStep: 'AUTH' as 'AUTH' | 'CARD' | 'PAGINATION' | 'OTHER' | 'EMPTY',
}
function recordPhase(phase: SafePhase) {
  progress.phase = phase
  const dest = path.resolve('build/village-history-real')
  mkdirSync(dest, { recursive: true })
  // 固定phaseと数値のみ。finallyが中断されても直前の待機箇所を保持する。
  writeFileSync(path.join(dest, 'ui-progress-safe.json'), JSON.stringify(progress, null, 2) + '\n', 'utf8')
}

async function showHistory(page: Page, action: () => Promise<unknown>, pageIndex: number,
  expectedIds: string[], total: number, actor: ActorFixture) {
  // 業務GETはブラウザ操作だけ。待機を操作前に登録する。
  const received = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/v1/village-join-requests/me'
      && url.searchParams.get('page') === String(pageIndex)
      && url.searchParams.get('size') === '20'
      && response.request().method() === 'GET'
  })
  recordPhase('HISTORY_ACTION_WAIT')
  await action()
  recordPhase('HISTORY_ACTION_DONE')
  recordPhase('HISTORY_RESPONSE_WAIT')
  const response = await received
  recordPhase('HISTORY_RESPONSE_DONE')
  expect(response.status()).toBe(200)
  recordPhase('HISTORY_BODY_WAIT')
  const payload = await response.json() as {
    data: { id: string; status: string; subjectType: string; message: string; reviewComment: string | null }[]
    meta: { page: number; size: number; total: number }
  }
  recordPhase('HISTORY_BODY_DONE')
  expect(payload.meta).toMatchObject({ page: pageIndex, size: 20, total })
  expect(payload.data.map((row) => row.id)).toEqual(expectedIds)
  const screen = page.getByTestId('my-village-join-requests')
  recordPhase('HISTORY_ASSERT_WAIT')
  await expect(screen).toBeVisible()
  await expect(screen.locator('[data-testid^="my-village-join-request-"]'))
    .toHaveCount(expectedIds.length)
  await expect.poll(async () => screen.locator('[data-testid^="my-village-join-request-"]')
    .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-testid')!
      .replace('my-village-join-request-', '')))).toEqual(expectedIds)
  for (const id of expectedIds) await expect(page.getByTestId(`my-village-join-request-${id}`)).toBeVisible()
  // 正準ja village.json: village.subjectType.USER / joinRequest.{approved,withdrawn,pending}
  const statusText = { APPROVED: '承認済み', WITHDRAWN: '取り下げ済み', REJECTED: '却下', PENDING: '審査待ち' }
  for (const row of actor.representativeRows.filter((row) => expectedIds.includes(row.id))) {
    expect(payload.data.find((actual) => actual.id === row.id)).toMatchObject(row)
    const card = page.getByTestId(`my-village-join-request-${row.id}`)
    await expect(card.getByText(statusText[row.status], { exact: true })).toBeVisible()
    await expect(card.getByText('個人', { exact: true })).toBeVisible()
    await expect(card.getByText(row.message, { exact: true })).toBeVisible()
    if (row.reviewComment !== null) await expect(card.getByText(row.reviewComment, { exact: true })).toBeVisible()
  }
  if (total === 0) await expect(screen.getByText('自分が送信した参加申請はありません', { exact: true })).toBeVisible()
  recordPhase('HISTORY_ASSERT_DONE')
  const dest = path.resolve(requiredEnv('VILLAGE_HISTORY_SAFE_OUTPUT'))
  if (dest !== path.resolve('build/village-history-real/safe-business')) throw new Error('SAFE_OUTPUT_BOUNDARY_INVALID')
  mkdirSync(dest, { recursive: true })
  const name = `${++evidenceOrdinal}-actor-${actor.userId}-page-${pageIndex}-${expectedIds.length}`
  // 履歴カード領域のみ。認証navigation/プロフィール/Storage/Cookieは回収しない。
  recordPhase('CAPTURE_WAIT')
  await screen.screenshot({ path: path.join(dest, `${name}.png`) })
  writeFileSync(path.join(dest, `${name}.dom.html`), await screen.innerHTML(), { encoding: 'utf8', flag: 'wx' })
  recordPhase('CAPTURE_DONE')
}

test('本人申請履歴_三本人を独立認証_導線とページと空と退村履歴を表示する', async ({ browser }) => {
  test.setTimeout(180_000)
  const fixture = JSON.parse(readFileSync(requiredEnv('VILLAGE_HISTORY_FIXTURE_MANIFEST'), 'utf8')) as Fixture
  expect(fixture.approvedOwnedFreshFixture).toBe(true)
  expect(fixture.actors.map((actor) => actor.expectedIds.length)).toEqual([21, 1, 0])
  expect(new Set(fixture.actors.flatMap((actor) => actor.expectedIds)).size).toBe(22)
  recordPhase('CONTEXT_WAIT')
  const contexts = await Promise.all(fixture.actors.map(() => browser.newContext({
    baseURL: requiredEnv('BASE_URL'), storageState: { cookies: [], origins: [] },
    locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: { width: 1280, height: 800 },
  })))
  recordPhase('CONTEXT_DONE')
  try {
    const pages: Page[] = []
    for (let index = 0; index < contexts.length; index++) {
      progress.actorIndex = index
      progress.meStatus = null
      recordPhase('PAGE_WAIT')
      const page = await contexts[index].newPage()
      recordPhase('PAGE_DONE')
      const prefix = fixture.actors[index].credentialEnvPrefix
      try {
        recordPhase('LOGIN_WAIT')
        await loginViaApi(page, {
          email: requiredEnv(`${prefix}_EMAIL`), password: requiredEnv(`${prefix}_PASSWORD`),
        }, { apiBaseUrl: requiredEnv('API_BASE_URL') })
        recordPhase('LOGIN_RETURNED')
      } catch {
        // 既auth helperの失敗文に資格情報を含めない。
        throw new Error(`独立本人${index + 1}の通常認証に失敗`)
      }
      pages.push(page)
      // login/setup専用API。業務履歴の代替GETではない。
      recordPhase('ME_WAIT')
      const me = await page.request.get(`${requiredEnv('API_BASE_URL')}/api/v1/users/me`)
      progress.meStatus = me.status()
      recordPhase('ME_RESPONSE')
      expect(me.status()).toBe(200)
      recordPhase('ME_BODY_WAIT')
      const principal = (await me.json()).data as { id: number; systemRole: string | null }
      recordPhase('ME_BODY_DONE')
      expect(principal.id).toBe(fixture.actors[index].userId)
      expect(principal.systemRole).not.toBe('SYSTEM_ADMIN')
      progress.canonicalLoginSuccesses++
      recordPhase('ME_VERIFIED')
    }
    const [owner, other, empty] = pages
    const [ownerFixture, otherFixture] = fixture.actors
    await test.step('マイページのカードから本人履歴へ進む', async () => {
      progress.actorIndex = 0
      progress.uiStep = 'CARD'
      recordPhase('NAVIGATION_WAIT')
      await owner.goto('/my')
      recordPhase('NAVIGATION_DONE')
      await showHistory(owner, () => owner.locator('a[href="/my/village-join-requests"]').click(),
        0, ownerFixture.expectedIds.slice(0, 20), 21, ownerFixture)
      recordPhase('HISTORY_ASSERT_WAIT')
      await expect(owner).toHaveURL(/\/my\/village-join-requests$/)
      recordPhase('HISTORY_ASSERT_DONE')
    })
    await test.step('次ページと前ページで重複なく安定順序を保つ', async () => {
      progress.uiStep = 'PAGINATION'
      // PrimeVue実DOMのsectionを使用。存在/一意でなければ前提失敗、DOM改変しない。
      await showHistory(owner, () => owner.locator('[data-pc-section="next"]').click(),
        1, ownerFixture.expectedIds.slice(20), 21, ownerFixture)
      await showHistory(owner, () => owner.locator('[data-pc-section="prev"]').click(),
        0, ownerFixture.expectedIds.slice(0, 20), 21, ownerFixture)
      await showHistory(owner, () => owner.reload(), 0, ownerFixture.expectedIds.slice(0, 20), 21, ownerFixture)
      expect(ownerFixture.leftVillageRequestId).toBeTruthy()
      expect(ownerFixture.expectedIds.slice(0, 20)).toContain(ownerFixture.leftVillageRequestId)
      recordPhase('HISTORY_ASSERT_WAIT')
      await expect(owner.getByTestId(`my-village-join-request-${ownerFixture.leftVillageRequestId}`)).toBeVisible()
      recordPhase('HISTORY_ASSERT_DONE')
    })
    await test.step('別村所属本人は自分だけを直URLで表示する', async () => {
      progress.actorIndex = 1
      progress.uiStep = 'OTHER'
      await showHistory(other, () => other.goto('/my/village-join-requests'), 0, otherFixture.expectedIds, 1, otherFixture)
      recordPhase('HISTORY_ASSERT_WAIT')
      for (const id of ownerFixture.expectedIds) await expect(other.getByTestId(`my-village-join-request-${id}`)).toHaveCount(0)
      for (const id of otherFixture.expectedIds) await expect(owner.getByTestId(`my-village-join-request-${id}`)).toHaveCount(0)
      recordPhase('HISTORY_ASSERT_DONE')
    })
    await test.step('村長であっても本人申請ゼロは空表示になる', async () => {
      progress.actorIndex = 2
      progress.uiStep = 'EMPTY'
      await showHistory(empty, () => empty.goto('/my/village-join-requests'), 0, [], 0, fixture.actors[2])
      recordPhase('HISTORY_ASSERT_WAIT')
      await expect(empty.getByText('自分が送信した参加申請はありません', { exact: true })).toBeVisible()
      await expect(empty.locator('[data-pc-name="paginator"]')).toHaveCount(0)
      recordPhase('HISTORY_ASSERT_DONE')
    })
    recordPhase('UI_COMPLETE')
  } finally {
    // UI失敗phaseを上書きせず、終了結果は別ui-session-cleanup.jsonに保存する。
    const cleanup = await Promise.all(contexts.map(async (context, index) => {
      let logoutStatus: number | null = null
      let authCookiesAbsent = false
      let closed = false
      try { logoutStatus = (await context.request.post(`${requiredEnv('API_BASE_URL')}/api/v1/auth/logout`)).status() }
      catch { /* 他contextの終了を妨げない */ }
      try { authCookiesAbsent = !(await context.cookies()).some((c) => ['access_token', 'refresh_token'].includes(c.name)) }
      catch { /* Cookie値を出さず未証明にする */ }
      try { await context.close(); closed = true } catch { /* 未証明として記録 */ }
      return { actorIndex: index, logoutStatus, authCookiesAbsent, closed }
    }))
    const dest = path.resolve('build/village-history-real')
    mkdirSync(dest, { recursive: true })
    writeFileSync(path.join(dest, 'ui-session-cleanup.json'), JSON.stringify({
      uiContexts: 3, uiLogoutSuccesses: cleanup.filter((c) => c.logoutStatus === 200).length,
      uiContextsClosed: cleanup.filter((c) => c.closed).length, observations: cleanup,
    }, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' })
    expect(cleanup.every((c) => c.logoutStatus === 200 && c.authCookiesAbsent && c.closed), 'UIセッション終了は別ゲート').toBe(true)
  }
})
