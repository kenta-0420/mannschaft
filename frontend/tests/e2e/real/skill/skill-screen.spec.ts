import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { waitForHydration } from '../../helpers/wait'

/**
 * CMP-261012-0313: チームの資格画面（/teams/{slug}/skills）の実機E2E（モックなし・実BE・実FE）。
 * 操作と確認は必ず画面（クリック・入力・表示）で行う。API はログイン・前提データ作成・後始末だけに使う。
 * 資格は新規登録で PENDING_REVIEW となり、ADMIN の承認で ACTIVE になる（マトリクスは ACTIVE/EXPIRED のみ）。
 */
test.use({ storageState: { cookies: [], origins: [] } })

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const pw = 'TestPass2026!'
const admin = { email: 'e2e-admin@test.mannschaft.local', password: pw }
const member = { email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local', password: process.env.TEST_USER_PASSWORD ?? pw }
// 同組織の別チームに所属し、対象チームには入っていないユーザー
const sameOrgNonMember = { email: 'e2e-dummy-1@test.mannschaft.local', password: pw }
// 対象組織に所属しない他テナントのユーザー
const otherTenant = { email: process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local', password: pw }
// このテストで DEPUTY_ADMIN / SUPPORTER にするユーザー（afterAll で元に戻す）
const deputyUser = { email: 'e2e-dummy-10@test.mannschaft.local', password: pw }
const supporterUser = { email: 'e2e-dummy-9@test.mannschaft.local', password: pw }
const TEAM_SLUG = 'e2e'
const ROLE_ID = { DEPUTY_ADMIN: 3, MEMBER: 4 } as const

interface MyTeam { id: number, slug: string, organizationId: number | null, role: string }
interface Session { ctx: BrowserContext, page: Page, userId: number }
type SessionKey = 'admin' | 'member' | 'same' | 'other' | 'deputy' | 'supporter'
interface SkillRow { id: number, name: string }
interface CategoryRow { id: number, name: string }

const tag = `SKS-${Date.now()}`
const catName = `${tag}-cat`
const catBName = `${tag}-catB`
const uiSkillName = `${tag}-UI資格`
const uiIssuer = `${tag}-UI発行元`
const otherSkillName = `${tag}-他人資格`
const otherIssuer = `${tag}-他人発行元`
const pendingSkillName = `${tag}-承認待ち資格`
const pendingIssuer = `${tag}-承認待ち発行元`
const activeSkillName = `${tag}-有効資格`
const activeIssuer = `${tag}-有効発行元`

const SKILL_API = /\/teams\/[^/]+\/(skills|skill-categories|skill-matrix)(\/|\?|$)/

const card = (page: Page, issuer: string) => page.locator('div.rounded-xl').filter({ hasText: issuer })

async function gotoSkills(page: Page) {
  await page.goto(`/teams/${TEAM_SLUG}/skills`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  const later = page.getByTestId('member-permission-setup')
  if (await later.isVisible()) {
    await later.getByRole('button', { name: 'あとで決める' }).click()
  }
  await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 30_000 })
}

/** 確認ダイアログ（PrimeVue ConfirmDialog。role=alertdialog）。承諾は p-button-danger、キャンセルは p-button-text。 */
const confirmDialog = (page: Page) => page.getByRole('alertdialog')

test.describe('資格画面の実機E2E（導線・ロール別表示・管理操作）', () => {
  test.setTimeout(300_000)
  test.describe.configure({ mode: 'serial' })

  const ctxs: BrowserContext[] = []
  const cleanupErrors: string[] = []
  const sessions: Partial<Record<SessionKey, Session>> = {}
  const sess = (key: SessionKey): Session => {
    const s = sessions[key]
    if (!s) throw new Error(`セッション未確立: ${key}`)
    return s
  }
  let teamId = 0
  let categoryId = 0
  let categoryBId = 0
  // ロール付与の状態（afterAll で元に戻すための記録）
  let deputyReady = false
  let deputyJoinedByTest = false
  let supporterReady = false
  let supporterFollowedByTest = false

  test.beforeAll(async ({ browser }) => {
    const open = async (c: { email: string, password: string }): Promise<Session> => {
      const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      ctxs.push(ctx)
      const page = await ctx.newPage()
      page.setDefaultTimeout(20_000)
      page.setDefaultNavigationTimeout(60_000)
      await loginViaApi(page, c, { apiBaseUrl: API })
      const me = await page.request.get(`${API}/api/v1/users/me`)
      expect(me.status()).toBe(200)
      const userId = ((await me.json()) as { data: { id: number } }).data.id
      return { ctx, page, userId }
    }
    sessions.admin = await open(admin)
    sessions.member = await open(member)
    sessions.same = await open(sameOrgNonMember)
    sessions.other = await open(otherTenant)
    sessions.deputy = await open(deputyUser)
    sessions.supporter = await open(supporterUser)

    const myTeams = async (s: Session) => (await (await s.ctx.request.get(`${API}/api/v1/me/teams`)).json() as { data: MyTeam[] }).data
    const adminTeam = (await myTeams(sess('admin'))).find(t => t.slug === TEAM_SLUG)
    expect(adminTeam, '管理者は対象チームの所属').toBeTruthy()
    teamId = adminTeam!.id
    expect(adminTeam!.role, '管理者の役割').toBe('ADMIN')
    const memberTeam = (await myTeams(sess('member'))).find(t => t.slug === TEAM_SLUG)
    expect(memberTeam?.role, '一般メンバーの役割').toBe('MEMBER')
    const sameTeams = await myTeams(sess('same'))
    expect(sameTeams.some(t => t.slug === TEAM_SLUG), '同組織非メンバーは対象チームに所属しない').toBe(false)
    expect((await myTeams(sess('other'))).some(t => t.slug === TEAM_SLUG), '他テナントは対象チームに所属しない').toBe(false)

    const a = sess('admin').ctx.request
    const mk = async (name: string) => {
      const res = await a.post(`${API}/api/v1/teams/${teamId}/skill-categories`, { data: { name } })
      expect(res.status(), await res.text()).toBe(201)
      return (await res.json() as { data: { id: number } }).data.id
    }
    categoryId = await mk(catName)
    categoryBId = await mk(catBName)

    // 他人（管理者）の資格。一般メンバーの一覧に出てはならない
    const other = await a.post(`${API}/api/v1/teams/${teamId}/skills`, {
      data: { skillCategoryId: categoryId, name: otherSkillName, issuer: otherIssuer },
    })
    expect(other.status(), await other.text()).toBe(201)

    // 一般メンバーの資格 2 件（確認待ち 1 件・有効 1 件）。有効側は管理者 API で承認して ACTIVE にする
    const m = sess('member').ctx.request
    const pending = await m.post(`${API}/api/v1/teams/${teamId}/skills`, {
      data: { skillCategoryId: categoryId, name: pendingSkillName, issuer: pendingIssuer },
    })
    expect(pending.status(), await pending.text()).toBe(201)
    const active = await m.post(`${API}/api/v1/teams/${teamId}/skills`, {
      data: { skillCategoryId: categoryId, name: activeSkillName, issuer: activeIssuer },
    })
    expect(active.status(), await active.text()).toBe(201)
    const activeId = (await active.json() as { data: { id: number } }).data.id
    const verify = await a.patch(`${API}/api/v1/teams/${teamId}/skills/${activeId}/verify`)
    expect(verify.status(), await verify.text()).toBe(200)

    // DEPUTY_ADMIN（MANAGE_MEMBER 権限なし）の用意: 未所属なら招待でMEMBER参加→role PATCHで昇格。既に所属していれば使わない
    const deputyTeam = (await myTeams(sess('deputy'))).find(t => t.slug === TEAM_SLUG)
    if (!deputyTeam) {
      const inv = await a.post(`${API}/api/v1/teams/${TEAM_SLUG}/invite-tokens`, { data: { roleId: ROLE_ID.MEMBER, maxUses: 1 } })
      if (inv.status() === 201) {
        const token = ((await inv.json()) as { data: { token: string } }).data.token
        const join = await sess('deputy').ctx.request.post(`${API}/api/v1/invite/${token}/join`)
        if (join.status() === 200) {
          deputyJoinedByTest = true
          const promote = await a.patch(`${API}/api/v1/teams/${TEAM_SLUG}/members/${sess('deputy').userId}/role`, { data: { roleId: ROLE_ID.DEPUTY_ADMIN } })
          deputyReady = promote.status() === 200
        }
      }
    } else if (deputyTeam.role === 'DEPUTY_ADMIN') {
      deputyReady = true
    }

    // SUPPORTER の用意: 未所属ならフォロー（自動承認設定ならAPPROVED）
    const supTeam = (await myTeams(sess('supporter'))).find(t => t.slug === TEAM_SLUG)
    if (!supTeam) {
      const follow = await sess('supporter').ctx.request.post(`${API}/api/v1/teams/${TEAM_SLUG}/follow`)
      if (follow.status() === 201 || follow.status() === 200) {
        supporterFollowedByTest = true
        const after = (await myTeams(sess('supporter'))).find(t => t.slug === TEAM_SLUG)
        supporterReady = after?.role === 'SUPPORTER'
      }
    } else {
      supporterReady = supTeam.role === 'SUPPORTER'
    }
  })

  test.afterAll(async () => {
    // 共有DBに残さない: 自タグの資格を全員分引いて削除 → 自タグのカテゴリを削除 → ロール付与を元に戻す
    const r = sessions.admin?.ctx.request
    if (r && teamId) {
      const found = await r.get(`${API}/api/v1/teams/${teamId}/skills/search?keyword=${encodeURIComponent(tag)}&includeExpired=true`)
      if (found.status() === 200) {
        const rows = (await found.json() as { data: { memberSkillId: number }[] }).data
        for (const row of rows) {
          const d = await r.delete(`${API}/api/v1/teams/${teamId}/skills/${row.memberSkillId}`)
          if (![200, 204, 404].includes(d.status())) cleanupErrors.push(`資格 ${row.memberSkillId}: HTTP ${d.status()}`)
        }
      } else {
        cleanupErrors.push(`資格検索: HTTP ${found.status()}`)
      }
      // 検索は発行元・資格名のキーワード一致のため、取りこぼしに備えて各本人の一覧からも削除する
      for (const key of ['member', 'admin'] as const) {
        const mine = await sessions[key]?.ctx.request.get(`${API}/api/v1/teams/${teamId}/skills/me`)
        if (mine?.status() === 200) {
          for (const row of (await mine.json() as { data: SkillRow[] }).data.filter(x => (x.name ?? '').startsWith(tag))) {
            const d = await sessions[key]!.ctx.request.delete(`${API}/api/v1/teams/${teamId}/skills/${row.id}`)
            if (![200, 204, 404].includes(d.status())) cleanupErrors.push(`本人資格 ${row.id}: HTTP ${d.status()}`)
          }
        }
      }
      const cats = await r.get(`${API}/api/v1/teams/${teamId}/skill-categories?includeInactive=true`)
      if (cats.status() === 200) {
        for (const c of (await cats.json() as { data: CategoryRow[] }).data.filter(x => x.name.startsWith(tag))) {
          const d = await r.delete(`${API}/api/v1/teams/${teamId}/skill-categories/${c.id}`)
          if (![200, 204, 404].includes(d.status())) cleanupErrors.push(`カテゴリ ${c.id}: HTTP ${d.status()}`)
        }
      } else {
        cleanupErrors.push(`カテゴリ一覧: HTTP ${cats.status()}`)
      }
      if (deputyJoinedByTest && sessions.deputy) {
        const d = await r.delete(`${API}/api/v1/teams/${TEAM_SLUG}/members/${sessions.deputy.userId}`)
        if (![200, 204, 404].includes(d.status())) cleanupErrors.push(`DEPUTY_ADMIN 除名: HTTP ${d.status()}`)
      }
    }
    if (supporterFollowedByTest && sessions.supporter) {
      const d = await sessions.supporter.ctx.request.delete(`${API}/api/v1/teams/${TEAM_SLUG}/follow`)
      if (![200, 204, 404].includes(d.status())) cleanupErrors.push(`サポーター解除: HTTP ${d.status()}`)
    }
    for (const c of ctxs) {
      try {
        await c.close()
      } catch (error) {
        cleanupErrors.push(`context close: ${String(error)}`)
      }
    }
    expect(cleanupErrors, `後始末失敗: ${cleanupErrors.join(' / ')}`).toEqual([])
  })

  test('1. 導線: MEMBER はサイドバーの「スキル・資格」から資格画面へ到達できる', async () => {
    const { page } = sess('member')
    await page.goto(`/teams/${TEAM_SLUG}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.getByTestId('scope-sidebar-toggle').click()
    const item = page.getByRole('link', { name: 'スキル・資格' })
    await expect(item, 'サイドバーにスキル・資格が出る').toBeVisible()
    await item.click()
    await expect(page).toHaveURL(new RegExp(`/teams/${TEAM_SLUG}/skills$`))
    await expect(page.getByRole('heading', { name: 'スキル・資格' }).first()).toBeVisible()
    await expect(page.getByRole('tab', { name: '自分のスキル・資格' })).toBeVisible()
  })

  test('1b. 導線: SUPPORTER にはサイドバーに「スキル・資格」が出ない', async () => {
    test.skip(!supporterReady, 'SUPPORTER を用意できなかった（フォロー不可または既存ロールが SUPPORTER でない）')
    const { page } = sess('supporter')
    await page.goto(`/teams/${TEAM_SLUG}`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    const toggle = page.getByTestId('scope-sidebar-toggle')
    if (await toggle.isVisible()) {
      await toggle.click()
      await expect(page.getByRole('link', { name: 'スキル・資格' })).toHaveCount(0)
    }
    await expect(page.getByRole('link', { name: 'スキル・資格' }), 'どの表示でも導線は無い').toHaveCount(0)
  })

  test('2. 一般メンバー: 自分の資格だけが出て、登録でき、削除は確認ダイアログを経る', async () => {
    const { page } = sess('member')
    await gotoSkills(page)
    await expect(page.getByRole('heading', { name: '自分のスキル・資格' }).first()).toBeVisible()
    // 他メンバーの資格は出ない・自分の資格は出る
    await expect(card(page, activeIssuer), '自分の資格は出る').toBeVisible()
    await expect(card(page, otherIssuer), '他人の資格は出ない').toHaveCount(0)
    await expect(page.getByText(otherSkillName)).toHaveCount(0)
    // 全員一覧・マトリクス・カテゴリ管理は出ない
    await expect(page.getByRole('tab', { name: 'スキルマトリクス' })).toHaveCount(0)
    await expect(page.getByRole('tab', { name: 'カテゴリ管理' })).toHaveCount(0)

    // 登録
    await page.getByRole('button', { name: 'スキル登録' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('combobox').first().click()
    await page.getByRole('option', { name: catName }).click()
    await dialog.getByPlaceholder('例: 普通自動車免許').fill(uiSkillName)
    await dialog.getByPlaceholder('例: 公安委員会').fill(uiIssuer)
    await dialog.getByRole('button', { name: '登録', exact: true }).click()
    const c = card(page, uiIssuer)
    await expect(c, '登録した資格が一覧に出る').toBeVisible()
    await expect(c.locator('h3'), '一覧に資格名が表示される').toHaveText(uiSkillName)

    // 削除: キャンセルでは消えない
    await c.locator('button:has(.pi-trash)').click()
    await expect(confirmDialog(page)).toBeVisible()
    await confirmDialog(page).locator('button.p-button-text').click()
    await expect(confirmDialog(page)).toHaveCount(0)
    await expect(card(page, uiIssuer), 'キャンセルしたので消えない').toBeVisible()
    const stillThere = await page.request.get(`${API}/api/v1/teams/${teamId}/skills/me`)
    expect(((await stillThere.json()) as { data: SkillRow[] }).data.some(x => x.name === uiSkillName), 'キャンセル後も BE に残る').toBe(true)

    // 削除: 承諾すると消える
    await card(page, uiIssuer).locator('button:has(.pi-trash)').click()
    await expect(confirmDialog(page)).toBeVisible()
    await confirmDialog(page).locator('button.p-button-danger').click()
    await expect(card(page, uiIssuer), '承諾したので消える').toHaveCount(0)
  })

  test('3. 管理者: 全員一覧・マトリクス・カテゴリ管理が見え、カテゴリ操作が再読込なしで反映される', async () => {
    const { page, ctx } = sess('admin')
    const memberId = sess('member').userId
    await gotoSkills(page)
    await expect(page.getByRole('tab', { name: 'スキル一覧' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'スキルマトリクス' })).toBeVisible()
    await expect(page.getByRole('tab', { name: 'カテゴリ管理' })).toBeVisible()
    // 全員一覧: 他メンバーの資格（一般メンバーの有効資格）が見える
    await expect(card(page, activeIssuer).locator('h3'), '全員一覧に一般メンバーの資格が出る').toHaveText(activeSkillName)

    // マトリクス: 有効な資格を持つメンバーの行・カテゴリ列・セルの状態
    await page.getByRole('tab', { name: 'スキルマトリクス' }).click()
    await expect(page.getByRole('columnheader', { name: catName }), 'カテゴリ列').toBeVisible()
    await expect(page.getByRole('columnheader', { name: catBName }), '資格の無いカテゴリ列').toBeVisible()
    await expect(page.getByTestId(`cell-${memberId}-${categoryId}`), '有効な資格のセル').toHaveAttribute('data-status', 'ACTIVE')
    await expect(page.getByTestId(`cell-${memberId}-${categoryBId}`), '資格が無いカテゴリのセル').toHaveAttribute('data-status', 'NONE')

    // カテゴリ作成 → 再読込なしで登録フォームの選択肢に出る
    await page.evaluate(() => { (window as unknown as { __noReload: boolean }).__noReload = true })
    const created = `${tag}-新カテゴリ`
    const renamed = `${tag}-改名後`
    await page.getByRole('tab', { name: 'カテゴリ管理' }).click()
    await page.getByRole('button', { name: 'カテゴリ追加' }).click()
    await page.getByRole('dialog').getByPlaceholder('例: 運転免許').fill(created)
    await page.getByRole('dialog').getByRole('button', { name: '作成', exact: true }).click()
    const catCard = (name: string) => page.locator('div.rounded-xl').filter({ has: page.locator('h3', { hasText: name }) })
    await expect(catCard(created), '作成したカテゴリが管理タブに出る').toBeVisible()
    // 作成直後のカテゴリに自分の資格を API で 1 件付け、一覧の名称解決を確かめる（同一ページのまま）
    const newCatId = (await (await ctx.request.get(`${API}/api/v1/teams/${teamId}/skill-categories`)).json() as { data: CategoryRow[] }).data.find(x => x.name === created)?.id
    expect(newCatId, '作成したカテゴリが API にも存在する').toBeTruthy()
    const sk = await ctx.request.post(`${API}/api/v1/teams/${teamId}/skills`, {
      data: { skillCategoryId: newCatId, name: `${tag}-新カテゴリ資格`, issuer: `${tag}-新カテゴリ発行元` },
    })
    expect(sk.status(), await sk.text()).toBe(201)
    await page.getByRole('tab', { name: 'スキル一覧' }).click()
    await page.getByRole('button', { name: 'スキル登録' }).click()
    await page.getByRole('dialog').getByRole('combobox').first().click()
    await expect(page.getByRole('option', { name: created }), '再読込なしで登録フォームの選択肢に出る').toBeVisible()
    await page.keyboard.press('Escape')
    await page.getByRole('dialog').getByRole('button', { name: 'キャンセル' }).click()

    // 改名 → 一覧のカテゴリ名に反映（再読込なし）
    await page.getByRole('tab', { name: 'カテゴリ管理' }).click()
    await catCard(created).locator('button:has(.pi-pencil)').click()
    await page.getByRole('dialog').getByPlaceholder('例: 運転免許').fill(renamed)
    await page.getByRole('dialog').getByRole('button', { name: '更新', exact: true }).click()
    await expect(catCard(renamed)).toBeVisible()
    await page.getByRole('tab', { name: 'スキル一覧' }).click()
    await expect(card(page, `${tag}-新カテゴリ発行元`).getByText(renamed), '一覧のカテゴリ名が改名後に変わる').toBeVisible()
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload), 'ページ再読込なし').toBe(true)

    // 削除は確認ダイアログを経る（先に資格を消して、キャンセル→承諾の順）
    const del = await ctx.request.get(`${API}/api/v1/teams/${teamId}/skills/me`)
    for (const row of (await del.json() as { data: SkillRow[] }).data.filter(x => x.name === `${tag}-新カテゴリ資格`)) {
      expect((await ctx.request.delete(`${API}/api/v1/teams/${teamId}/skills/${row.id}`)).status()).toBeLessThan(300)
    }
    await page.getByRole('tab', { name: 'カテゴリ管理' }).click()
    await catCard(renamed).locator('button:has(.pi-trash)').click()
    await expect(confirmDialog(page)).toBeVisible()
    await confirmDialog(page).locator('button.p-button-text').click()
    await expect(catCard(renamed), 'キャンセルでは消えない').toBeVisible()
    await catCard(renamed).locator('button:has(.pi-trash)').click()
    await confirmDialog(page).locator('button.p-button-danger').click()
    await expect(catCard(renamed), '承諾すると消える').toHaveCount(0)
  })

  test('4. 証明書ボタン: 証明書のない資格には証明書ボタンが出ない', async () => {
    const { page } = sess('admin')
    await gotoSkills(page)
    await expect(card(page, activeIssuer)).toBeVisible()
    await expect(card(page, activeIssuer).locator('button:has(.pi-download)'), '証明書なしの資格に証明書ボタンは出ない').toHaveCount(0)
    // 操作ボタン自体は出ている（ボタン群が丸ごと欠けているわけではない）
    await expect(card(page, activeIssuer).locator('button:has(.pi-pencil)')).toHaveCount(1)
  })

  test('5. 承認: ADMIN が確認待ちの資格を承認でき、表示が確認待ち→有効に変わる', async () => {
    const { page } = sess('admin')
    await gotoSkills(page)
    const c = card(page, pendingIssuer)
    await expect(c.locator('h3'), '一覧の項目名が正しく出る').toHaveText(pendingSkillName)
    await expect(c.getByText('確認待ち', { exact: true })).toBeVisible()
    await c.locator('button:has(.pi-check)').click()
    await expect(page.locator('.p-toast-message').filter({ hasText: 'スキルを承認しました' })).toBeVisible()
    const after = card(page, pendingIssuer)
    await expect(after.getByText('有効', { exact: true }), '承認後は有効').toBeVisible()
    await expect(after.getByText('確認待ち', { exact: true })).toHaveCount(0)
    await expect(after.locator('button:has(.pi-check)'), '承認ボタンは消える').toHaveCount(0)
    await expect(after.getByText('承認済み')).toBeVisible()
  })

  for (const [label, key] of [['同組織の非メンバー', 'same'], ['他テナント', 'other']] as const) {
    test(`6. ${label}: URL 直打ちで権限なしの案内が1つだけ出て、失敗トーストも資格系 API 呼び出しも無い`, async () => {
      const { page } = sess(key)
      const calls: string[] = []
      const onRequest = (req: { url: () => string }) => {
        if (SKILL_API.test(req.url())) calls.push(req.url())
      }
      page.on('request', onRequest)
      try {
        await page.goto(`/teams/${TEAM_SLUG}/skills`, { waitUntil: 'domcontentloaded' })
        await waitForHydration(page)
        await expect(page.locator('.pi-spin, .p-progressspinner')).toHaveCount(0, { timeout: 30_000 })
        await expect(page.locator('.pi-lock'), '鍵アイコンの空状態が1つ').toHaveCount(1)
        await expect(page.getByText('このチームのメンバーのみ、スキル・資格を利用できます')).toHaveCount(1)
        // 描画が落ち着いてもトーストが出ない
        await page.waitForTimeout(3_000)
        await expect(page.locator('.p-toast-message'), '失敗トーストは出ない').toHaveCount(0)
        await expect(page.getByRole('tab')).toHaveCount(0)
        await expect(page.getByText(tag)).toHaveCount(0)
        expect(calls, `資格系 API へのリクエストが発生した: ${calls.join(', ')}`).toEqual([])
      } finally {
        page.off('request', onRequest)
      }
    })
  }

  test('7. MANAGE_MEMBER を持たない DEPUTY_ADMIN: マトリクス・全員一覧が出ず、自分の資格だけ', async () => {
    test.skip(!deputyReady, 'DEPUTY_ADMIN を用意できなかった（招待参加またはロール昇格に失敗）')
    const { page } = sess('deputy')
    const calls: string[] = []
    const onRequest = (req: { url: () => string }) => {
      if (/\/skills\/search|\/skill-matrix/.test(req.url())) calls.push(req.url())
    }
    page.on('request', onRequest)
    try {
      await gotoSkills(page)
      await expect(page.getByRole('tab', { name: '自分のスキル・資格' })).toBeVisible()
      await expect(page.getByRole('tab', { name: 'スキル一覧' })).toHaveCount(0)
      await expect(page.getByRole('tab', { name: 'スキルマトリクス' })).toHaveCount(0)
      await expect(page.getByRole('tab', { name: 'カテゴリ管理' })).toHaveCount(0)
      await expect(card(page, activeIssuer), '他人の資格は出ない').toHaveCount(0)
      await page.waitForTimeout(2_000)
      await expect(page.locator('.p-toast-message'), '失敗トーストは出ない').toHaveCount(0)
      expect(calls, `閲覧権限の無い API を呼んだ: ${calls.join(', ')}`).toEqual([])
    } finally {
      page.off('request', onRequest)
    }
  })

  // GUEST は作れる範囲に無い（チームの GUEST 付与手段が API にない）ため、ここでは扱わない。
})
