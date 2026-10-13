import { expect, test, type APIResponse, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

/**
 * 資格APIの越境応答を不在と完全一致させる契約（PR #3790）の実機E2E。
 * 各ロールは実ログインのセッションで画面を踏む。page.request による API 直叩きは「補助」であり、
 * 画面の確認を置き換えない。
 */
test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const pw = 'TestPass2026!'
// 本人: 画面から登録できるのは canManage（ADMIN/DEPUTY）のみのため、チーム管理者アカウントを本人役にする
const owner = { email: 'e2e-admin@test.mannschaft.local', password: pw }
// 同チームの一般メンバー（本人でも管理者でもない）
const member = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: process.env.TEST_USER_PASSWORD ?? pw }
// 同組織の別チームに所属し、対象チームには入っていないユーザー
const sameOrgNonMember = { email: 'e2e-dummy-1@test.mannschaft.local', password: pw }
// 対象組織に所属しない他テナントのユーザー
const otherTenant = { email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password: pw }
const TEAM_SLUG = 'e2e'

interface MyTeam { id: number, slug: string, organizationId: number | null, role: string }
interface MyOrg { id: number, role: string }
interface Session { ctx: BrowserContext, page: Page }
type SessionKey = 'owner' | 'member' | 'same' | 'other'
interface Shape { status: number, code: string | null, message: string | null }

const tag = `SKL-${Date.now()}`
const skillName = `${tag}-資格`
const editedName = `${tag}-資格(編集後)`
// カードの特定には issuer（発行元）に入れた自タグを使う（資格名は h3 に表示されることを別途確認する）
const uiIssuer = `${tag}-UI発行元`
const apiSkillName = `${tag}-API資格`
const apiIssuer = `${tag}-API発行元`

async function shape(res: APIResponse): Promise<Shape> {
  // 応答本文が JSON でない場合は握りつぶさず、本文なし(null)として形を比較する
  const text = await res.text()
  const body = (text.startsWith('{') ? JSON.parse(text) : {}) as { error?: { code?: string, message?: string } }
  return { status: res.status(), code: body.error?.code ?? null, message: body.error?.message ?? null }
}

async function openSkillsPage(page: Page) {
  await page.goto(`/teams/${TEAM_SLUG}/skills`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const later = page.getByTestId('member-permission-setup')
  if (await later.isVisible()) {
    await later.getByRole('button', { name: 'あとで決める' }).click()
  }
  await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 30_000 })
}

const card = (page: Page, issuer: string) => page.locator('div.rounded-xl').filter({ hasText: issuer })

test.describe('資格画面のロール横断と越境応答の一致', () => {
  test.setTimeout(300_000)
  const ctxs: BrowserContext[] = []
  const cleanupErrors: string[] = []
  const sessions: Partial<Record<SessionKey, Session>> = {}
  // 未ログインのロールを取り出したら握りつぶさず明示的に失敗させる
  const sess = (key: SessionKey): Session => {
    const s = sessions[key]
    if (!s) throw new Error(`セッション未確立: ${key}`)
    return s
  }
  let teamId = 0
  let categoryId = 0
  let categoryName = ''
  let apiSkillId = 0
  const observed: string[] = []

  test.beforeAll(async ({ browser }) => {
    const open = async (c: { email: string, password: string }): Promise<Session> => {
      const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      ctxs.push(ctx)
      const page = await ctx.newPage()
      page.setDefaultTimeout(20_000)
      page.setDefaultNavigationTimeout(60_000)
      await loginViaApi(page, c, { apiBaseUrl: API })
      return { ctx, page }
    }
    sessions.owner = await open(owner)
    sessions.member = await open(member)
    sessions.same = await open(sameOrgNonMember)
    sessions.other = await open(otherTenant)

    // 前提: 各ロールの所属関係を API で実証する
    const myTeams = async (s: Session) => (await (await s.ctx.request.get(`${API}/api/v1/me/teams`)).json() as { data: MyTeam[] }).data
    const myOrgs = async (s: Session) => (await (await s.ctx.request.get(`${API}/api/v1/me/organizations`)).json() as { data: MyOrg[] }).data
    const ownerTeam = (await myTeams(sess('owner'))).find(t => t.slug === TEAM_SLUG)
    expect(ownerTeam, '本人は対象チームの所属').toBeTruthy()
    teamId = ownerTeam!.id
    const orgId = ownerTeam!.organizationId
    expect(orgId, '対象チームは組織配下').not.toBeNull()
    const memberTeam = (await myTeams(sess('member'))).find(t => t.slug === TEAM_SLUG)
    expect(memberTeam, '一般メンバーは対象チームの所属').toBeTruthy()
    expect(memberTeam!.role, '一般メンバーの役割').toBe('MEMBER')
    const sameTeams = await myTeams(sess('same'))
    expect(sameTeams.some(t => t.slug === TEAM_SLUG), '同組織非メンバーは対象チームに所属しない').toBe(false)
    expect(sameTeams.some(t => t.organizationId === orgId), '同組織非メンバーは同組織の別チームに所属').toBe(true)
    expect((await myTeams(sess('other'))).some(t => t.slug === TEAM_SLUG || t.organizationId === orgId), '他テナントは対象組織・チームに無関係').toBe(false)
    expect((await myOrgs(sess('other'))).some(o => o.id === orgId), '他テナントは対象組織に所属しない').toBe(false)

    // 前提データ（API 作成。afterAll で削除）: 共有チーム e2e には資格カテゴリが無いため管理者 API で自タグのカテゴリを作る。
    // ロール別の検証 2・3 用の「本人の資格」も API で作る（手順 1 の UI 登録とは別物）
    categoryName = `${tag}-cat`
    const cat = await sess('owner').ctx.request.post(`${API}/api/v1/teams/${teamId}/skill-categories`, { data: { name: categoryName } })
    expect(cat.status(), await cat.text()).toBe(201)
    categoryId = (await cat.json() as { data: { id: number } }).data.id
    const sk = await sess('owner').ctx.request.post(`${API}/api/v1/teams/${teamId}/skills`, {
      data: { skillCategoryId: categoryId, name: apiSkillName, issuer: apiIssuer },
    })
    expect(sk.status(), await sk.text()).toBe(201)
    apiSkillId = (await sk.json() as { data: { id: number } }).data.id
  })

  test.afterAll(async () => {
    // 共有DBに残さない。自タグの資格を本人の一覧 API から全件引いて削除し、最後にカテゴリを削除する
    const r = sessions.owner?.ctx.request
    if (r && teamId) {
      const mine = await r.get(`${API}/api/v1/teams/${teamId}/skills/me`)
      if (mine.status() === 200) {
        const rows = (await mine.json() as { data: { id: number, name: string }[] }).data
        for (const row of rows.filter(x => (x.name ?? '').startsWith(tag))) {
          const d = await r.delete(`${API}/api/v1/teams/${teamId}/skills/${row.id}`)
          if (![204, 404].includes(d.status())) cleanupErrors.push(`資格 ${row.id}: HTTP ${d.status()}`)
        }
      } else {
        cleanupErrors.push(`資格一覧取得: HTTP ${mine.status()}`)
      }
      if (categoryId) {
        const d = await r.delete(`${API}/api/v1/teams/${teamId}/skill-categories/${categoryId}`)
        if (![200, 204].includes(d.status())) cleanupErrors.push(`カテゴリ ${categoryId}: HTTP ${d.status()}`)
      }
    }
    for (const c of ctxs) {
      try {
        await c.close()
      } catch (error) {
        cleanupErrors.push(`context close: ${String(error)}`)
      }
    }
    console.log(`[観測]\n${observed.join('\n')}`)
    expect(cleanupErrors, `後始末失敗: ${cleanupErrors.join(' / ')}`).toEqual([])
  })

  test('1. 本人: 画面から資格を登録・編集・削除できる', async () => {
    const { page } = sess('owner')
    await openSkillsPage(page)
    await page.getByRole('button', { name: 'スキル登録' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('combobox').first().click()
    await page.getByRole('option', { name: categoryName }).click()
    await dialog.getByPlaceholder('例: 普通自動車免許').fill(skillName)
    await dialog.getByPlaceholder('例: 公安委員会').fill(uiIssuer)
    await dialog.getByRole('button', { name: '登録', exact: true }).click()
    const c = card(page, uiIssuer)
    await expect(c, '登録した資格が一覧に出る').toBeVisible()
    await page.screenshot({ path: test.info().outputPath('owner-list.png'), fullPage: true })
    observed.push(`本人: 登録直後のカード表示=${(await c.innerText()).replace(/\s+/g, ' ')}`)
    await expect(c.locator('h3'), '一覧に資格名が表示される').toHaveText(skillName)

    await c.locator('button:has(.pi-pencil)').click()
    const edit = page.getByRole('dialog')
    await edit.getByPlaceholder('例: 普通自動車免許').fill(editedName)
    const update = edit.getByRole('button', { name: '更新' })
    await expect(update, '編集ダイアログの更新ボタンが押せる').toBeEnabled()
    await update.click()
    await expect(card(page, uiIssuer).locator('h3'), '編集が一覧に反映される').toHaveText(editedName)

    await card(page, uiIssuer).locator('button:has(.pi-trash)').click()
    // 削除は確認ダイアログ（role=alertdialog）を経る。承諾（p-confirmdialog-accept-button）で初めて消える
    await expect(page.getByRole('alertdialog')).toBeVisible()
    await page.getByRole('alertdialog').locator('button.p-confirmdialog-accept-button').click()
    await expect(card(page, uiIssuer), '削除すると一覧から消える').toHaveCount(0)
  })

  test('2. 同チームの一般メンバー: 登録ボタンは出るが他人の資格は一覧に出ず、個別IDの API は 403', async () => {
    const { page } = sess('member')
    await openSkillsPage(page)
    // 一般メンバーも自分の資格を登録できる。一覧は本人分（/skills/me）だけ
    await expect(page.getByRole('button', { name: 'スキル登録' }), '一般メンバーにも登録ボタンが出る').toBeVisible()
    await expect(card(page, apiIssuer), '他人（管理者）の資格は一覧に出ない').toHaveCount(0)
    await expect(page.getByText(apiSkillName), '他人の資格名は画面のどこにも出ない').toHaveCount(0)
    observed.push(`一般メンバー: 画面文言(抜粋)=${(await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 300)}`)
    await page.screenshot({ path: test.info().outputPath('member-list.png'), fullPage: true })
    // 補助 API
    const r = await shape(await page.request.get(`${API}/api/v1/teams/${teamId}/skills/${apiSkillId}`))
    observed.push(`補助API 一般メンバー GET: ${JSON.stringify(r)}`)
    expect(r.status).toBe(403)
    expect(r.code).toBe('COMMON_002')
  })

  for (const [label, key] of [['同組織の非メンバー', 'same'], ['他テナント', 'other']] as const) {
    test(`3. ${label}: URL 直打ちで他人の資格名が見えず、実在IDと不在IDの応答が完全一致`, async () => {
      const { page } = sess(key)
      await page.goto(`/teams/${TEAM_SLUG}/skills`, { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 30_000 })
      // 描画が落ち着くまでの固定観測期間（拒否表示・通知・空表示のどれが出るかを記録する）
      await page.waitForTimeout(3_000)
      // 非メンバーには権限なしの案内（鍵アイコンの空状態）が1つ出て、失敗トーストは出ない
      await expect(page.locator('.pi-lock'), '鍵アイコンの空状態が1つ').toHaveCount(1)
      await expect(page.locator('.p-toast-message'), '失敗トーストは出ない').toHaveCount(0)
      const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ')
      observed.push(`${label}: URL=${page.url()} 画面文言(抜粋)=${body.slice(0, 300)}`)
      await page.screenshot({ path: test.info().outputPath(`${key}.png`), fullPage: true })
      expect(body).not.toContain(tag)
      expect(body).not.toContain(apiSkillName)

      // 補助 API
      const real = await shape(await page.request.get(`${API}/api/v1/teams/${teamId}/skills/${apiSkillId}`))
      const absent = await shape(await page.request.get(`${API}/api/v1/teams/${teamId}/skills/${apiSkillId + 10_000_000}`))
      observed.push(`補助API ${label} 実在: ${JSON.stringify(real)} / 不在: ${JSON.stringify(absent)}`)
      expect(real).toEqual(absent)
      expect(absent.status).toBe(404)
      expect(absent.code).toBe('SKILL_002')
    })
  }
})
