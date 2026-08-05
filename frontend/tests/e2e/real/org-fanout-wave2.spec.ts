/**
 * 実機E2E（モック不使用・実BE/実FE）: 通知fan-out Wave-2 ORGスコープの高粒度回帰テスト。
 *
 * 背景: Wave-2（#2602）はORGスコープ×ALL配信のアンケート公開を耐久fan-outジョブ
 * （notification_fanout_jobs）へ移譲した。旧同期経路ではなく新worker経路が実際に
 * 発火し、SUPPORTERトグル・母集団絞り・重複排除が成立することを、ブラウザ到達と
 * DB白箱の両面で検証する。
 *
 * 前提: 実BE(API_BASE_URL, 既定 http://localhost:8080) / 実FE(BASE_URL) が起動済み。
 * 既定は本陣標準ポート(BE:8080)。検証用worktree等で別ポートのスタックを使う場合はAPI_BASE_URL/BASE_URLを上書きすること。
 * 認証: e2e-admin@test.mannschaft.local（公開者・SYSTEM_ADMIN）/
 *   e2e-user@test.mannschaft.local（fc-u-18 MEMBER）/
 *   e2e-supporter@test.mannschaft.local（fc-u-18 SUPPORTER）/
 *   e2e-outsider@test.mannschaft.local（非所属）。全員パスワード TestPass2026!。
 *
 * DB照会（notification_fanout_jobs / notifications）は本specの範囲外（別途スクリプトで実施し
 * scratchpad/wave2e2e 配下にファイル保存する。ACの一部はこのspec外のDB照会で裏取りする）。
 */
import { test, expect, type APIRequestContext } from '@playwright/test'

const BACKEND_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD }
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const E2E_SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: PASSWORD }
// NOTE: 実DBには e2e-outsider@test.mannschaft.local が存在しなかった（seed drift）。
// 代わりに fcTokyo(org id=9) 本体・配下チーム(team_org_memberships: team 1/2/11/12) いずれの
// memberships / user_roles にも属さないことを実DB照会で確認済みの e2e-dummy-10 を非所属ユーザーとして
// 採用する（2026-08-05 実測。e2e-dummy-1 はteam1/12にMEMBERとして所属しておりNG、reservation-authz specの
// OUTSIDER_EMAIL=e2e-dummy-6 も本specでは要再検証のため使わず自前でDB照会し直した）。
const E2E_OUTSIDER = { email: 'e2e-dummy-10@test.mannschaft.local', password: PASSWORD }

async function loginToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
  const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok()) return null
  return (await res.json())?.data?.accessToken ?? null
}

async function backendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5000 })
    return (await res.json())?.status === 'UP'
  } catch {
    return false
  }
}

async function fetchFcTokyoOrg(
  request: APIRequestContext,
  token: string,
): Promise<{ id: number; slug: string }> {
  const res = await request.get(`${BACKEND_URL}/api/v1/me/organizations`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.ok(), '所属組織一覧取得').toBeTruthy()
  const body = await res.json()
  const orgs: Array<{ id: number; slug: string; name?: string }> = body?.data ?? []
  const fcTokyo = orgs.find((o) => (o.name ?? '').includes('FC東京'))
  expect(fcTokyo, 'fcTokyo組織(FC東京)が見つかること').toBeTruthy()
  return { id: fcTokyo!.id, slug: fcTokyo!.slug }
}

async function createAndPublishOrgSurvey(
  request: APIRequestContext,
  adminToken: string,
  orgSlug: string,
  includeSupporters: boolean,
): Promise<{ surveyId: number; title: string }> {
  const title = `【実機E2E Wave2 ORG】fan-out回帰 includeSupporters=${includeSupporters} ${Date.now()}`
  const create = await request.post(`${BACKEND_URL}/api/v1/organizations/${orgSlug}/surveys`, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` },
    data: {
      title,
      description: '実機E2E Wave-2 ORG fan-out回帰。afterAllで削除。',
      isAnonymous: false,
      allowMultipleSubmissions: false,
      resultsVisibility: 'AFTER_RESPONSE',
      distributionMode: 'ALL',
      includeSupporters,
      unrespondedVisibility: 'ALL_MEMBERS',
      questions: [
        { questionType: 'SINGLE_CHOICE', questionText: '好きな季節は？', isRequired: true, displayOrder: 1,
          options: [{ optionText: '春', displayOrder: 1 }, { optionText: '夏', displayOrder: 2 }] },
      ],
    },
  })
  expect(create.status(), `アンケート作成(includeSupporters=${includeSupporters})`).toBe(201)
  const surveyId = (await create.json())?.data?.survey?.id
  expect(surveyId, 'surveyId').toBeTruthy()

  const pub = await request.post(
    `${BACKEND_URL}/api/v1/organizations/${orgSlug}/surveys/${surveyId}/publish`,
    { headers: { Authorization: `Bearer ${adminToken}` } },
  )
  expect(pub.ok(), 'アンケート公開').toBeTruthy()
  return { surveyId, title }
}

interface NotificationRow {
  id: number
  notificationType?: string
  sourceType?: string
  sourceId?: number
  body?: string
  title?: string
  actionUrl?: string
  createdAt?: string
}

async function pollForSurveyNotification(
  request: APIRequestContext,
  token: string,
  surveyId: number,
  { timeoutMs = 60000, intervalMs = 2000 }: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<NotificationRow | null> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const res = await request.get(`${BACKEND_URL}/api/v1/notifications?page=0&size=50`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (res.ok()) {
      const body = await res.json()
      const list: NotificationRow[] = body?.data ?? []
      // NOTE: actionUrl の部分一致は surveyId=5 が "/surveys/50" 等に誤爆するため使わない。
      // sourceType/sourceId の完全一致、または actionUrl の完全一致のみで判定する（偽陽性防止）。
      const hit = list.find(
        (n) =>
          (n.sourceType === 'SURVEY' && n.sourceId === surveyId) ||
          n.actionUrl === `/surveys/${surveyId}`,
      )
      if (hit) return hit
    }
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  return null
}

async function countSurveyNotifications(
  request: APIRequestContext,
  token: string,
  surveyId: number,
): Promise<number> {
  const res = await request.get(`${BACKEND_URL}/api/v1/notifications?page=0&size=50`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok()) return -1
  const body = await res.json()
  const list: NotificationRow[] = body?.data ?? []
  // NOTE: actionUrl の部分一致は偽陽性（surveyId=5 が "/surveys/50" 等に誤爆）を生むため使わない。
  return list.filter(
    (n) =>
      (n.sourceType === 'SURVEY' && n.sourceId === surveyId) ||
      n.actionUrl === `/surveys/${surveyId}`,
  ).length
}

test.describe('WAVE2-ORG-FANOUT: 通知fan-out Wave-2 ORGスコープ実機回帰', () => {
  let adminToken: string
  let orgId: number
  let orgSlug: string
  let surveyAllTrue: number
  let surveyAllTrueTitle: string
  let surveyAllFalse: number

  test.beforeAll(async ({ request }) => {
    test.skip(!(await backendAlive(request)), 'BE 未起動のためスキップ')
    const at = await loginToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    test.skip(!at, 'admin ログイン不可のためスキップ')
    adminToken = at!
    const org = await fetchFcTokyoOrg(request, adminToken)
    orgId = org.id
    orgSlug = org.slug

    // AC-1/2/4/5 用: includeSupporters=true のORG×ALL公開
    const surveyTrue = await createAndPublishOrgSurvey(request, adminToken, orgSlug, true)
    surveyAllTrue = surveyTrue.surveyId
    surveyAllTrueTitle = surveyTrue.title
    // AC-3(b) 用: includeSupporters=false のORG×ALL公開（別サーベイ）
    const surveyFalse = await createAndPublishOrgSurvey(request, adminToken, orgSlug, false)
    surveyAllFalse = surveyFalse.surveyId
  })

  test.afterAll(async ({ request }) => {
    for (const surveyId of [surveyAllTrue, surveyAllFalse]) {
      if (surveyId && adminToken && orgSlug) {
        await request
          .delete(`${BACKEND_URL}/api/v1/organizations/${orgSlug}/surveys/${surveyId}`, {
            headers: { Authorization: `Bearer ${adminToken}` },
          })
          .catch(() => {})
      }
    }
  })

  test('WAVE2-1: AC-1 ブラウザ到達 — e2e-userがincludeSupporters=true公開のSURVEY_CREATEDを/notificationsで確認', async ({
    page,
    request,
  }) => {
    // 白箱ポーリングで到達を待ってからブラウザ確認へ（耐久fan-outは非同期のため数秒〜のラグを許容）
    const userToken = await loginToken(request, E2E_USER.email, E2E_USER.password)
    expect(userToken, 'e2e-user ログイン').toBeTruthy()
    const hit = await pollForSurveyNotification(request, userToken!, surveyAllTrue, { timeoutMs: 60000 })
    expect(hit, 'e2e-userのSURVEY_CREATED到達（API白箱ポーリング）').toBeTruthy()

    // ブラウザでUIログイン→/notificationsへ到達し当該通知を可視確認
    await page.goto('/login')
    await page.getByRole('button', { name: 'ログイン', exact: true }).waitFor({ timeout: 20000 })
    await page.locator('input#email').fill(E2E_USER.email)
    await page.locator('input[type="password"]').fill(E2E_USER.password)
    await page.getByRole('button', { name: 'ログイン', exact: true }).click()
    await page.waitForURL(/\/my\/|\/dashboard/, { timeout: 30000 })

    await page.goto('/notifications')
    // 通知本文は「「{title}」が公開されました。回答にご協力ください。」（SurveyPublishNotificationListener）。
    // タイトルにDate.now()を含めユニーク化しているため部分一致で当該通知を一意に特定できる。
    await expect(page.getByText(surveyAllTrueTitle, { exact: false }).first()).toBeVisible({ timeout: 20000 })

    // ヘッダのベル（未読件数バッジ）にも反映されること（白箱: unread-count APIで裏取り）
    const unreadRes = await request.get(`${BACKEND_URL}/api/v1/notifications/unread-count`, {
      headers: { Authorization: `Bearer ${userToken}` },
    })
    expect(unreadRes.ok(), 'unread-count取得').toBeTruthy()
    const unreadBody = await unreadRes.json()
    const unreadCount = unreadBody?.data?.unreadCount ?? unreadBody?.data ?? 0
    expect(unreadCount, 'AC-1: 未読件数が1件以上（当該通知を含む）').toBeGreaterThan(0)
  })

  test('WAVE2-3: AC-3 SUPPORTERトグル二値', async ({ request }) => {
    const supporterToken = await loginToken(request, E2E_SUPPORTER.email, E2E_SUPPORTER.password)
    expect(supporterToken, 'e2e-supporter ログイン').toBeTruthy()
    const memberToken = await loginToken(request, E2E_USER.email, E2E_USER.password)
    expect(memberToken, 'e2e-user ログイン').toBeTruthy()

    // (a) includeSupporters=true → supporterに到達
    const supporterHitTrue = await pollForSurveyNotification(request, supporterToken!, surveyAllTrue, {
      timeoutMs: 60000,
    })
    expect(supporterHitTrue, 'AC-3(a): supporterがincludeSupporters=true公開に到達').toBeTruthy()

    // (b) includeSupporters=false → supporterには到達しない・memberには到達する
    const memberHitFalse = await pollForSurveyNotification(request, memberToken!, surveyAllFalse, {
      timeoutMs: 60000,
    })
    expect(memberHitFalse, 'AC-3(b): MEMBERはincludeSupporters=false公開にも到達').toBeTruthy()

    // supporterの不在は猶予を置いた上でassert（falseサーベイのfan-outが完了してから確認する）
    const supporterHitFalse = await pollForSurveyNotification(request, supporterToken!, surveyAllFalse, {
      timeoutMs: 15000,
      intervalMs: 3000,
    })
    expect(supporterHitFalse, 'AC-3(b): supporterはincludeSupporters=false公開に到達しない').toBeNull()
  })

  test('WAVE2-4: AC-4 母集団絞り — 非所属e2e-outsiderには到達しない', async ({ request }) => {
    const outsiderToken = await loginToken(request, E2E_OUTSIDER.email, E2E_OUTSIDER.password)
    expect(outsiderToken, 'e2e-outsider ログイン').toBeTruthy()
    const memberToken = await loginToken(request, E2E_USER.email, E2E_USER.password)
    expect(memberToken, 'e2e-user ログイン').toBeTruthy()

    // 先にmemberの到達を確認しfan-out完了済みであることを確定してからoutsiderの不在を見る
    const memberHit = await pollForSurveyNotification(request, memberToken!, surveyAllTrue, { timeoutMs: 60000 })
    expect(memberHit, 'AC-4: fan-out完了確認（member到達）').toBeTruthy()

    const outsiderHit = await pollForSurveyNotification(request, outsiderToken!, surveyAllTrue, {
      timeoutMs: 10000,
      intervalMs: 2000,
    })
    expect(outsiderHit, 'AC-4: 非所属e2e-outsiderには到達しない').toBeNull()
  })

  test('WAVE2-5: AC-5 重複なし — e2e-userのSURVEY_CREATED(該当surveyId)はちょうど1件', async ({ request }) => {
    const memberToken = await loginToken(request, E2E_USER.email, E2E_USER.password)
    expect(memberToken, 'e2e-user ログイン').toBeTruthy()
    // fan-out完了を待つ
    const hit = await pollForSurveyNotification(request, memberToken!, surveyAllTrue, { timeoutMs: 60000 })
    expect(hit, 'AC-5前提: fan-out完了確認').toBeTruthy()

    const count = await countSurveyNotifications(request, memberToken!, surveyAllTrue)
    expect(count, 'AC-5: e2e-userのSURVEY_CREATED重複なし（ちょうど1件）').toBe(1)
  })
})
