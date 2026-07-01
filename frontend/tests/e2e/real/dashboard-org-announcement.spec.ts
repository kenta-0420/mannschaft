import { test, expect, request as playwrightRequest, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/**
 * 実機 E2E: チームダッシュボードの「組織告知」ポジティブパス（重複排除ハードニングの裏取り）。
 *
 * <p>検証内容:
 * 組織 A（既存 seed 組織）配下に新規チーム B を作り、e2e-user（F）を招待トークンで B に所属させ、
 * 組織 A から全チーム宛の告知を broadcast したうえで、F がチーム B のダッシュボードを取得すると
 * その告知が <b>ちょうど 1 件</b>（重複なし）表示されることを end-to-end で確認する。</p>
 *
 * <p><b>重複回帰の証明は unit テスト</b>
 * {@code DashboardServiceOrgAnnouncementDedupTest#AC1_多重orgロール_同一feedIdは1件に重複排除}
 * が担う（実 DB は user_roles の UNIQUE(user_id, scope_key) ゆえ「重複 org ロール」を API で作れないため、
 * 実機側は同告知が二重化しないポジティブパスの裏取りに徹する）。</p>
 *
 * <p>前提（seed 依存・memory project_e2e_test_user_provisioning 準拠、総当り禁止）:
 * <ul>
 *   <li>e2e-admin / e2e-user が存在し TestPass2026! でログインできること</li>
 *   <li>{@code E2E_SHARED_ORG_ID}（既定 71）に e2e-admin が ADMIN、e2e-user が何らかの org ロールを持つこと
 *       （= F の user_roles に organization_id={ORG} が存在し、ダッシュボードの組織告知経路が発火する）</li>
 * </ul>
 * 実行例: {@code API_BASE_URL=http://localhost:8081 BASE_URL=http://localhost:3001
 *   npx playwright test --config=playwright-real.config.ts dashboard-org-announcement}</p>
 */

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const PASSWORD = process.env.TEST_PASSWORD ?? 'TestPass2026!'
// 既存 seed 組織（e2e-admin=ADMIN / e2e-user=org ロール保有）。上書き可。
const ORG_ID = Number(process.env.E2E_SHARED_ORG_ID ?? '71')
const MEMBER_ROLE_ID = Number(process.env.E2E_MEMBER_ROLE_ID ?? '4')

async function bearer(ctx: APIRequestContext, email: string): Promise<string> {
  const res = await ctx.post('/api/v1/auth/login', { data: { email, password: PASSWORD } })
  expect(res.ok(), `login ${email}: ${res.status()}`).toBeTruthy()
  const token = (await res.json()).data?.accessToken as string
  expect(token, `accessToken for ${email}`).toBeTruthy()
  return token
}

test.describe('チームダッシュボード 組織告知 ポジティブパス（重複排除ハードニング）', () => {
  test('組織A発の全チーム宛告知が、配下チームBのダッシュボードにちょうど1件表示される', async ({ page }, testInfo) => {
    // dev SSR ダッシュボードは重く、WebSocket/ポーリングで networkidle が発火しないため余裕を持たせる。
    test.setTimeout(150_000)
    const api = await playwrightRequest.newContext({ baseURL: API_BASE })
    const stamp = Date.now()
    const slug = `e2e-orgnotice-${stamp}`
    const noticeTitle = `OrgNoticeDedupE2E-${stamp}`

    const adminToken = await bearer(api, ADMIN_EMAIL)
    const userToken = await bearer(api, USER_EMAIL)
    const adminH = { Authorization: `Bearer ${adminToken}` }
    const userH = { Authorization: `Bearer ${userToken}` }

    // 1. admin が配下チーム B を新規作成
    const createTeam = await api.post('/api/v1/teams', {
      headers: adminH,
      data: { name: `OrgNotice E2E Team ${stamp}`, slug },
    })
    expect(createTeam.status(), await createTeam.text()).toBe(201)

    // 2. admin が MEMBER 招待トークンを発行 → F が join（memberships 行を実 API で作成）
    const inviteRes = await api.post(`/api/v1/teams/${slug}/invite-tokens`, {
      headers: adminH,
      data: { roleId: MEMBER_ROLE_ID, expiresIn: '1d', maxUses: 10 },
    })
    expect(inviteRes.status(), await inviteRes.text()).toBe(201)
    const inviteToken = (await inviteRes.json()).data.token as string

    const joinRes = await api.post(`/api/v1/invite/${inviteToken}/join`, { headers: userH })
    expect(joinRes.ok(), `join: ${joinRes.status()} ${await joinRes.text()}`).toBeTruthy()

    // 3. 組織 A から「全チーム宛（targetTeamIds 省略）」の告知を broadcast
    const broadcastRes = await api.post(`/api/v1/organizations/${ORG_ID}/broadcast`, {
      headers: adminH,
      data: {
        channel: 'TIMELINE_POST',
        targetRole: 'PUBLIC',
        priority: 'NORMAL',
        content: { title: noticeTitle, body: `組織Aから配信された告知（重複排除E2E） ${stamp}` },
      },
    })
    expect(broadcastRes.status(), await broadcastRes.text()).toBe(201)
    const feedId = (await broadcastRes.json()).data.announcementFeedId as number
    expect(feedId).toBeGreaterThan(0)

    // 4. F がチーム B ダッシュボードを取得 → 当該 feedId がちょうど 1 件（重複なし）
    const dashRes = await api.get(`/api/v1/dashboard/team/${slug}?statsPeriod=WEEK`, { headers: userH })
    expect(dashRes.status(), await dashRes.text()).toBe(200)
    const dash = (await dashRes.json()).data
    const notices: Array<{ id: number; title_cache?: string }> = dash.teamNotices ?? []

    const occurrences = notices.filter((n) => n.id === feedId).length
    expect(occurrences, `feedId=${feedId} はダッシュボードに 1 件だけ（重複排除）`).toBe(1)
    const mine = notices.find((n) => n.id === feedId)
    expect(mine?.title_cache).toBe(noticeTitle)
    // ダッシュボードは MEMBER として告知ウィジェットが可視であること
    expect(dash.viewer_role).toBe('MEMBER')

    await api.dispose()

    // 5. ブラウザで F の認証済みダッシュボードを描画してスクリーンショットを保存（視覚的裏取り）。
    //    重複排除の断定は上記 API アサーションが権威。ここは描画確認とスクショ取得に徹する。
    await loginViaApi(page, { email: USER_EMAIL, password: PASSWORD })
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    // ハイドレーション + カルーセル初期描画を待つ（WS/ポーリングで networkidle は発火しないため固定待機）。
    // チーム B の告知ウィジェット描画はスコープカルーセル UX 依存で不安定なため、ここは
    // 「F の認証済みダッシュボードが描画されること」の視覚的裏取りに徹する（重複排除の断定は上記 API）。
    await page.waitForTimeout(8_000)
    const shotPath = testInfo.outputPath(`dashboard-org-announcement-${stamp}.png`)
    await page.screenshot({ path: shotPath })
    testInfo.attachments.push({ name: 'team-dashboard', path: shotPath, contentType: 'image/png' })
  })
})
