import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { test, expect, type Page } from '@playwright/test'
import {
  RUN,
  type Actor,
  type Scope,
  affiliate,
  apiAs,
  archiveScope,
  clickAndWait,
  createActor,
  createOrg,
  createTeam,
  dismissPermissionSetup,
  enableAdminLens,
  FANOUT_WAIT_MS,
  joinScope,
  must,
  notificationsOf,
  openAs,
  putSettings,
  sql,
  systemAdmin,
  triggerBatch,
  typeOf,
  resetUserRateLimit,
  browserApiOrigins,
} from './helpers/f0121-org-groups-world'

/**
 * F01.2.1 チーム加盟（申請・承認・拒否・招待・離脱・除名・制限・権限）の実機E2E（CMP-261011-0035）。
 *
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081
 *   node node_modules/playwright/cli.js test -c playwright-real.config.ts --project=chromium-real --no-deps \
 *   tests/e2e/real/f0121-org-affiliation.real.spec.ts
 *
 * API を使うのは、ログイン・前提作成（利用者・組織・チーム・所属）・後始末・観測（通知一覧・DB）と、
 * 受け入れ条件そのものが「API を直接呼ぶと 4xx」と定めている契約の確認（API-CONTRACT の test）だけ。
 * 対象操作（設定・申請・承認・拒否・招待・承諾・辞退・離脱・除名・制限解除・権限付与）は画面から行う。
 *
 * 固定人物（設計書 §16）: XA/XD/XM（組織X）、TA/TD/TM/TG（チームT）、YA（組織Y）、UA（Y 加盟チームU）、
 * N（どこにも属さない）、SA（SYSTEM_ADMIN 単独）。
 */

interface World {
  XA: Actor
  XD: Actor
  XM: Actor
  TA: Actor
  TD: Actor
  TM: Actor
  TG: Actor
  YA: Actor
  UA: Actor
  N: Actor
  X: Scope
  Y: Scope
  T: Scope
  U: Scope
  P: Scope
}

// 失敗でワーカーが再起動しても同じ世界を使い続ける（親プロセス単位でキャッシュ）。
const cacheDir = path.resolve('test-results')
const cacheFile = path.join(cacheDir, `f0121-affiliation-world-${process.ppid}.json`)
let w: World

async function buildWorld(): Promise<World> {
  const actors: Actor[] = []
  for (const label of ['XA', 'XD', 'XM', 'TA', 'TD', 'TM', 'TG', 'YA', 'UA', 'N']) actors.push(await createActor(label, 'aff'))
  const [XA, XD, XM, TA, TD, TM, TG, YA, UA, N] = actors
  const X = await createOrg(XA, `実機組織X ${RUN}`)
  const Y = await createOrg(YA, `実機組織Y ${RUN}`)
  const T = await createTeam(TA, `実機チームT ${RUN}`)
  const U = await createTeam(UA, `実機チームU ${RUN}`)
  // XA から見えない非公開チーム（AC-D09）
  const P = await createTeam(UA, `実機非公開P ${RUN}`, 'MEMBERS_AND_ABOVE')
  await joinScope('organizations', XA, X.slug, XD, 'DEPUTY_ADMIN')
  await joinScope('organizations', XA, X.slug, XM, 'MEMBER')
  await joinScope('teams', TA, T.slug, TD, 'DEPUTY_ADMIN')
  await joinScope('teams', TA, T.slug, TM, 'MEMBER')
  await joinScope('teams', TA, T.slug, TG, 'MEMBER')
  // 組織Y にチームU を加盟させる（前提。Y は申請受付 on・グループ on）
  await putSettings(YA, Y.slug, {
    teamApplicationEnabled: false,
    teamGroupsEnabled: true,
    applicationGroupMode: 'OFF',
  })
  await affiliate(YA, Y.slug, UA, U.slug)
  return { XA, XD, XM, TA, TD, TM, TG, YA, UA, N, X, Y, T, U, P }
}

test.describe.configure({ mode: 'default' })
test.use({ screenshot: 'only-on-failure', trace: 'off' })

test.beforeAll(async () => {
  test.setTimeout(900_000)
  if (existsSync(cacheFile)) {
    w = JSON.parse(readFileSync(cacheFile, 'utf8')) as World
    return
  }
  w = await buildWorld()
  mkdirSync(cacheDir, { recursive: true })
  writeFileSync(cacheFile, JSON.stringify(w, null, 2))
  // 証拠用に固定人物と対象を出力する（パスワードは共通の検証用のみ）
  console.log(
    `[F0121] world X=${w.X.slug} Y=${w.Y.slug} T=${w.T.slug} U=${w.U.slug} P=${w.P.slug} ` +
      `XA=${w.XA.id} XD=${w.XD.id} XM=${w.XM.id} TA=${w.TA.id} TD=${w.TD.id} TM=${w.TM.id} TG=${w.TG.id} YA=${w.YA.id} N=${w.N.id}`,
  )
})

/** チームT の、組織X に対する加盟行（membershipId と status）を DB から読む（観測）。 */
function membershipRow(orgSlug: string, teamSlug: string): { id: string; status: string; direction: string; groupId: string } | null {
  const out = sql(
    `SELECT m.id, m.status, m.direction, IFNULL(BIN_TO_UUID(m.group_id),'') FROM team_org_memberships m ` +
      `JOIN organizations o ON o.id=m.organization_id JOIN teams t ON t.id=m.team_id ` +
      `WHERE o.slug='${orgSlug}' AND t.slug='${teamSlug}';`,
  )
  if (!out) return null
  const [id, status, direction, groupId] = out.split('\t')
  return { id, status, direction, groupId }
}

async function close(...contexts: { context: { close(): Promise<void> } }[]) {
  for (const c of contexts) await c.context.close()
}

async function selectOption(page: Page, testId: string, optionName: string | RegExp) {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: optionName }).filter({ visible: true }).first().click()
}

/** 通知ベルから通知一覧へ行き、指定 ID の通知行をクリックする（AC-L03 / L06）。 */
async function clickNotificationFromBell(page: Page, notificationId: number) {
  const bell = page.getByRole('button', { name: '通知' }).first()
  await expect(bell).toBeVisible({ timeout: 30_000 })
  await bell.click()
  await page.locator('.notif-row').first().click()
  await expect(page).toHaveURL(/\/notifications/, { timeout: 30_000 })
  const row = page.locator(`[data-notification-id="${notificationId}"]`)
  await expect(row).toBeVisible({ timeout: 30_000 })
  await row.click()
}

/** 通知一覧（API で観測）に、今回の対象 slug を含む指定種別の通知が届くまで待つ。 */
async function waitNotification(actor: Actor, type: string, slug: string) {
  const predicate = (n: { actionUrl?: string; body?: string; title?: string }) =>
    `${n.actionUrl ?? ''}`.includes(slug)
  let found: Awaited<ReturnType<typeof notificationsOf>>[number] | undefined
  await expect
    .poll(
      async () => {
        found = (await notificationsOf(actor)).find((n) => typeOf(n) === type && predicate(n))
        return Boolean(found)
      },
      { timeout: 60_000, intervals: [2_000] },
    )
    .toBe(true)
  return found!
}

test.describe('F01.2.1 加盟の実機E2E', () => {
  test('ORGAFF-01 [AC-A01/A02/A05/L01] XA が組織トップ→所属チーム→加盟の設定で受付を on にし、保持更新しても on のまま', async ({ browser }) => {
    test.setTimeout(600_000)
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const page = xa.page
    try {
      await dismissPermissionSetup(page)
      // 導線: 組織トップ → 「所属チーム」タブ → 管理レンズ → 「加盟の設定」
      await page.getByRole('link', { name: '所属チーム' }).first().click()
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams`))
      await enableAdminLens(page)
      await page.getByTestId('team-affiliation-settings-link').click()
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/settings/team-affiliation$`))
      // AC-A01: 新規組織は既定 off / off / 選ばせない
      const appToggle = page.locator('#settings-application-enabled')
      const groupsToggle = page.locator('#settings-groups-enabled')
      await expect(appToggle).not.toBeChecked({ timeout: 30_000 })
      await expect(groupsToggle).not.toBeChecked()
      await expect(page.getByTestId('settings-group-mode')).toContainText('選ばせない')

      // AC-A05: グループ機能 off のまま「必ず選ばせる」で保存 → 422 ORG_070、画面に保存失敗
      await selectOption(page, 'settings-group-mode', '必ず選ばせる')
      const rejected = await clickAndWait(
        page,
        () => page.getByTestId('settings-save').click(),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`,
      )
      expect(rejected.status()).toBe(422)
      expect((await rejected.json()).error.code).toBe('ORG_070')
      await expect(page.getByTestId('settings-save-error')).toBeVisible()
      await selectOption(page, 'settings-group-mode', '選ばせない')

      // AC-A02: 受付 on → 保存 200
      await page.locator('label[for="settings-application-enabled"]').click()
      await expect(appToggle).toBeChecked()
      await page.locator('#settings-guidance').fill('実機E2Eの案内文です')
      const saved = await clickAndWait(
        page,
        () => page.getByTestId('settings-save').click(),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`,
      )
      expect(saved.status()).toBe(200)
      await expect(page.getByText('設定を保存しました').first()).toBeVisible()

      // 永続化: 再読込でも on・案内文が残る。そのまま（同データ保持で）案内文だけ変えて再保存しても on のまま
      await page.reload()
      await expect(appToggle).toBeChecked({ timeout: 30_000 })
      await expect(page.locator('#settings-guidance')).toHaveValue('実機E2Eの案内文です')
      await page.locator('#settings-guidance').fill('実機E2Eの案内文です（更新）')
      const resaved = await clickAndWait(
        page,
        () => page.getByTestId('settings-save').click(),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`,
      )
      expect(resaved.status()).toBe(200)
      expect(resaved.request().postDataJSON()).toMatchObject({ teamApplicationEnabled: true })
      await page.reload()
      await expect(appToggle).toBeChecked({ timeout: 30_000 })
      await expect(page.locator('#settings-guidance')).toHaveValue('実機E2Eの案内文です（更新）')

      // AC-A02 後半: 組織の公開 API で受付 on が見える（観測）
      const org = await apiAs(w.TA, 'GET', `/api/v1/organizations/${w.X.slug}`)
      expect(JSON.stringify(org.body)).toContain('"teamApplication"')

      // AC-L01 後半: 管理コンソールのカードからも到達できる
      await page.goto(`/organizations/${w.X.slug}/admin`)
      await page.getByRole('link', { name: /チーム加盟の設定/ }).first().click()
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/settings/team-affiliation$`))
      await expect(page.getByTestId('team-affiliation-settings-form')).toBeVisible({ timeout: 30_000 })
    } finally {
      await close(xa)
    }
  })

  test('ORGAFF-02 [AC-A03/A04/K01] XD・XM は設定画面を直打ちすると組織トップへ戻され、未ログインはログインへ。API も 403/401', async ({ browser }) => {
    test.setTimeout(600_000)
    for (const actor of [w.XD, w.XM]) {
      const s = await openAs(browser, actor, `/organizations/${w.X.slug}/settings/team-affiliation`)
      try {
        await expect(s.page).toHaveURL(new RegExp(`/organizations/${w.X.slug}$`), { timeout: 30_000 })
        await expect(s.page.getByTestId('team-affiliation-settings-form')).toHaveCount(0)
        // 組織トップの所属チーム画面に「加盟の設定」導線が出ない
        await s.page.goto(`/organizations/${w.X.slug}/member-teams`)
        await expect(s.page.getByTestId('team-affiliation-settings-link')).toHaveCount(0)
      } finally {
        await close(s)
      }
      const get = await apiAs(actor, 'GET', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
      expect(get.status, `${actor.label} GET`).toBe(403)
    }
    for (const actor of [w.TA, w.YA]) {
      const get = await apiAs(actor, 'GET', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
      expect(get.status, `${actor.label} GET`).toBe(403)
      const put = await apiAs(actor, 'PUT', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`, {
        teamApplicationEnabled: false,
        teamGroupsEnabled: false,
        applicationGroupMode: 'OFF',
      })
      expect(put.status, `${actor.label} PUT`).toBe(403)
    }
    const anon = await apiAs(null, 'GET', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
    expect(anon.status).toBe(401)
    const guest = await openAs(browser, null, `/organizations/${w.X.slug}/settings/team-affiliation`)
    try {
      await expect(guest.page).toHaveURL(/\/login/, { timeout: 30_000 })
    } finally {
      await close(guest)
    }
  })

  test('ORGAFF-03 [AC-A08/A09/A10/A11/L02/B01/B15] TA が所属組織→加盟先を探す→受付中の X→申請を送信。権限のない人には申請ボタンが出ない', async ({ browser }) => {
    test.setTimeout(600_000)
    // AC-A08: 受付 off の組織Y のページを TA が開いてもボタンが出ない
    const offPage = await openAs(browser, w.TA, `/public/organizations/${w.Y.slug}`)
    try {
      await expect(offPage.page.getByText(w.Y.name).first()).toBeVisible({ timeout: 30_000 })
      await expect(offPage.page.getByTestId('team-affiliation-apply-button')).toHaveCount(0)
    } finally {
      await close(offPage)
    }

    // AC-A09 / A11 負: TM・TD・N（ログイン中）・未ログインには、受付 on の X でもボタンが出ない
    for (const actor of [w.TM, w.TD, w.N, null]) {
      const s = await openAs(browser, actor, `/public/organizations/${w.X.slug}`)
      try {
        await expect(s.page.getByText(w.X.name).first()).toBeVisible({ timeout: 30_000 })
        // eligibility の応答を待ってからボタンの不在を確かめる
        await s.page.waitForTimeout(2_000)
        await expect(s.page.getByTestId('team-affiliation-apply-button'), actor?.label ?? '未ログイン').toHaveCount(0)
      } finally {
        await close(s)
      }
    }

    // AC-L02 / A10 / A11 正: TA は URL を手で打たずに申請まで到達する
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}`)
    const page = ta.page
    try {
      await page.getByRole('link', { name: '所属組織' }).first().click()
      await expect(page).toHaveURL(new RegExp(`/teams/${w.T.slug}/affiliations`))
      const discover = page.getByTestId('discover-organizations-link')
      await expect(discover).toBeVisible({ timeout: 30_000 })
      const searched = await clickAndWait(page, () => discover.click(), 'GET', '/api/v1/public/organizations/search')
      expect(new URL(searched.url()).searchParams.get('acceptingTeamApplications')).toBe('true')
      await expect(page).toHaveURL(/\/discover\/organizations\?acceptingTeamApplications=true/)
      await expect(page.getByTestId('filter-accepting-chip')).toHaveAttribute('aria-pressed', 'true')
      // 受付中に絞った結果だけ出る: 名前で絞って X は出て Y（受付 off）は出ない
      await page.getByPlaceholder('組織名で検索').fill(`実機組織`)
      const narrowed = await clickAndWait(
        page,
        () => page.getByRole('button', { name: '検索', exact: true }).click(),
        'GET',
        '/api/v1/public/organizations/search',
      )
      expect(new URL(narrowed.url()).searchParams.get('acceptingTeamApplications')).toBe('true')
      const cardX = page.getByTestId('discover-organization-card').filter({ hasText: w.X.name })
      await expect(cardX).toBeVisible({ timeout: 30_000 })
      await expect(page.getByText(w.Y.name)).toHaveCount(0)
      await expect(cardX.getByTestId('accepting-badge')).toHaveText(/加盟受付中/)
      await cardX.getByRole('link').click()
      await expect(page).toHaveURL(new RegExp(`/(public/)?organizations/${w.X.slug}`), { timeout: 30_000 })
      const applyButton = page.getByTestId('team-affiliation-apply-button')
      await expect(applyButton).toBeVisible({ timeout: 30_000 })
      await applyButton.click()
      const dialog = page.getByTestId('team-affiliation-apply-dialog')
      await expect(dialog).toBeVisible()
      await expect(page.getByTestId('apply-guidance')).toContainText('実機E2Eの案内文です（更新）')
      await page.getByTestId('apply-team-select').click()
      await page.getByRole('option', { name: new RegExp(w.T.name) }).filter({ visible: true }).first().click()
      // AC-B15（画面）: 501 文字では件数が「501 / 500」になり送信できない。500 文字なら送信できる
      await page.getByTestId('apply-message').fill('あ'.repeat(501))
      await expect(page.getByTestId('apply-message-count')).toContainText('501 / 500')
      await expect(page.getByTestId('apply-submit')).toBeDisabled()
      await page.getByTestId('apply-message').fill('あ'.repeat(500))
      await expect(page.getByTestId('apply-message-count')).toContainText('500 / 500')
      await expect(page.getByTestId('apply-submit')).toBeEnabled()
      await page.getByTestId('apply-message').fill('実機E2Eからの加盟申請です')
      const applied = await clickAndWait(
        page,
        () => page.getByTestId('apply-submit').click(),
        'POST',
        `/api/v1/teams/${w.T.slug}/org-applications`,
      )
      expect(applied.status(), await applied.text()).toBe(201)
      await expect(page.getByText('加盟を申請しました。組織の承認をお待ちください').first()).toBeVisible()
      // 永続化: チームの「申請中」一覧に X が出る
      await page.goto(`/teams/${w.T.slug}/affiliations?view=applications`)
      await expect(page.getByTestId('application-row').filter({ hasText: w.X.name })).toBeVisible({ timeout: 30_000 })
      const row = membershipRow(w.X.slug, w.T.slug)
      expect(row).toMatchObject({ status: 'PENDING', direction: 'TEAM_APPLY' })
    } finally {
      await close(ta)
    }
    // AC-A10 後半: 受付中フィルタの URL を直接開いても同じ絞り込み
    const direct = await openAs(browser, w.N, `/discover/organizations?acceptingTeamApplications=true`)
    try {
      await expect(direct.page.getByTestId('filter-accepting-chip')).toHaveAttribute('aria-pressed', 'true', { timeout: 30_000 })
    } finally {
      await close(direct)
    }
  })

  test('ORGAFF-04 [AC-L03/C01/K02/P07] XA が通知ベルの申請通知から申請一覧に着地して承認。XM・XD は管理 view を直打ちしても一覧へ戻される', async ({ browser }) => {
    test.setTimeout(600_000)
    const notification = await waitNotification(w.XA, 'TEAM_ORG_APPLICATION_RECEIVED', w.X.slug)
    expect(notification.actionUrl).toBe(`/organizations/${w.X.slug}/member-teams?view=applications`)
    // 申請の通知は XD・XM には届かない（組織 ADMIN のみ）
    expect((await notificationsOf(w.XM)).some((n) => typeOf(n) === 'TEAM_ORG_APPLICATION_RECEIVED' && `${n.actionUrl}`.includes(w.X.slug))).toBe(false)

    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const page = xa.page
    try {
      await dismissPermissionSetup(page)
      await clickNotificationFromBell(page, notification.id)
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams\\?view=applications`), { timeout: 30_000 })
      await enableAdminLens(page)
      const row = page.getByTestId('application-row').filter({ hasText: w.T.name })
      await expect(row).toBeVisible({ timeout: 30_000 })
      await expect(row).toContainText('実機E2Eからの加盟申請です')
      await row.getByRole('button', { name: '承認' }).click()
      const dialog = page.getByTestId('team-application-approve-dialog')
      await expect(dialog).toBeVisible()
      const approved = await clickAndWait(
        page,
        () => page.getByTestId('approve-submit').click(),
        'POST',
        new RegExp(`/api/v1/organizations/${w.X.slug}/team-applications/[^/]+/approve$`),
      )
      expect(approved.status(), await approved.text()).toBe(200)
      await expect(page.getByText('承認しました').first()).toBeVisible()
      // 所属チーム一覧に T が出る（永続化）
      await page.getByTestId('view-tab-teams').click()
      await expect(page.getByText(w.T.name).first()).toBeVisible({ timeout: 30_000 })
      expect(membershipRow(w.X.slug, w.T.slug)?.status).toBe('ACTIVE')
    } finally {
      await close(xa)
    }
    // AC-K02: XM・XD は管理 view を直打ちしても一覧へ戻され、切替も出ない
    for (const actor of [w.XM, w.XD]) {
      for (const view of ['applications', 'invites', 'restrictions', 'groups']) {
        const s = await openAs(browser, actor, `/organizations/${w.X.slug}/member-teams?view=${view}`)
        try {
          await expect(s.page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams$`), { timeout: 30_000 })
          await expect(s.page.getByTestId('member-teams-view-switcher')).toHaveCount(0)
          await expect(s.page.getByTestId(`member-teams-${view}`)).toHaveCount(0)
        } finally {
          await close(s)
        }
      }
      const apiDenied = await apiAs(actor, 'GET', `/api/v1/organizations/${w.X.slug}/team-applications`)
      expect(apiDenied.status, `${actor.label} 申請一覧 API`).toBe(403)
    }
    // AC-P07: 承認通知は TA と（権限付与前なので）TA のみ。TM・TD には届かない
    await waitNotification(w.TA, 'TEAM_ORG_APPLICATION_APPROVED', w.T.slug)
    await new Promise((resolve) => setTimeout(resolve, FANOUT_WAIT_MS))
    for (const actor of [w.TM, w.TD]) {
      expect((await notificationsOf(actor)).some((n) => typeOf(n) === 'TEAM_ORG_APPLICATION_APPROVED' && `${n.actionUrl}`.includes(w.T.slug)), actor.label).toBe(false)
    }
  })
  test('ORGAFF-05 [AC-E01/E09] TA が所属組織画面から X を離脱すると行が消え、XA に離脱通知（リンクは所属チーム）が届き TA・TG には届かない', async ({ browser }) => {
    test.setTimeout(600_000)
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}/affiliations`)
    try {
      const row = ta.page.getByTestId('org-row').filter({ hasText: w.X.name })
      await expect(row).toBeVisible({ timeout: 30_000 })
      await ta.page.getByTestId(`org-leave-${w.X.slug}`).click()
      await expect(ta.page.getByText(`「${w.X.name}」から離脱します。`)).toBeVisible()
      const left = await clickAndWait(ta.page, () => ta.page.getByTestId('org-leave-confirm').click(), 'DELETE', `/api/v1/teams/${w.T.slug}/organizations/${w.X.slug}`)
      expect(left.status()).toBe(204)
      await expect(row).toHaveCount(0)
      await ta.page.reload()
      await expect(ta.page.getByTestId('org-row').filter({ hasText: w.X.name })).toHaveCount(0, { timeout: 30_000 })
    } finally {
      await close(ta)
    }
    expect(membershipRow(w.X.slug, w.T.slug)).toBeNull()
    const n = await waitNotification(w.XA, 'TEAM_ORG_MEMBERSHIP_LEFT', w.X.slug)
    expect(n.actionUrl).toBe(`/organizations/${w.X.slug}/member-teams`)
    await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
    for (const actor of [w.TA, w.TG]) {
      expect((await notificationsOf(actor)).some((x) => typeOf(x) === 'TEAM_ORG_MEMBERSHIP_LEFT' && `${x.actionUrl}`.includes(w.X.slug)), actor.label).toBe(false)
    }
    // XA は通知から所属チーム画面へ着地する
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    try {
      await clickNotificationFromBell(xa.page, n.id)
      await expect(xa.page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams$`), { timeout: 30_000 })
    } finally {
      await close(xa)
    }
  })

  /** TA が組織ページの申請ボタンから X へ申請する（画面操作）。 */
  async function applyViaUi(page: Page, expectedStatus: number) {
    await page.goto(`/organizations/${w.X.slug}`)
    const button = page.getByTestId('team-affiliation-apply-button')
    await expect(button).toBeVisible({ timeout: 30_000 })
    await button.click()
    await expect(page.getByTestId('team-affiliation-apply-dialog')).toBeVisible()
    await page.getByTestId('apply-team-select').click()
    await page.getByRole('option', { name: new RegExp(w.T.name) }).filter({ visible: true }).first().click()
    const applied = await clickAndWait(page, () => page.getByTestId('apply-submit').click(), 'POST', `/api/v1/teams/${w.T.slug}/org-applications`)
    expect(applied.status(), await applied.text()).toBe(expectedStatus)
    return applied
  }

  /** XA が申請一覧で T の申請を拒否する（画面操作）。 */
  async function rejectViaUi(page: Page, reason: string, block: boolean) {
    await page.goto(`/organizations/${w.X.slug}/member-teams?view=applications`)
    await enableAdminLens(page)
    const row = page.getByTestId('application-row').filter({ hasText: w.T.name })
    await expect(row).toBeVisible({ timeout: 30_000 })
    await row.getByRole('button', { name: '拒否' }).click()
    await expect(page.getByTestId('team-application-reject-dialog')).toBeVisible()
    if (reason) await page.getByTestId('reject-reason').fill(reason)
    if (block) await page.locator('label[for="reject-block"]').click()
    const rejected = await clickAndWait(page, () => page.getByTestId('reject-submit').click(), 'POST', new RegExp(`/team-applications/[^/]+/reject$`))
    expect(rejected.status(), await rejected.text()).toBe(200)
    await expect(page.getByText('拒否しました').first()).toBeVisible()
    await expect(row).toHaveCount(0)
  }

  test('ORGAFF-06 [AC-C07/C09/C10/C11/C12/B13/C08] 拒否（理由・ブロック）→制限解除→再申請→取下げ→冷却→時計を進めて再申請→拒否→30日後に再申請→承認', async ({ browser }) => {
    test.setTimeout(1200_000)
    const ta = await openAs(browser, w.TA, `/organizations/${w.X.slug}`)
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    let blockedBody: unknown
    try {
      await applyViaUi(ta.page, 201)
      // AC-C07 / C09: 理由付き・ブロックで拒否
      await rejectViaUi(xa.page, '今期の受付は終了しました', true)
      const rejectedNotice = await waitNotification(w.TA, 'TEAM_ORG_APPLICATION_REJECTED', w.T.slug)
      expect(`${rejectedNotice.title ?? ''}${(rejectedNotice as { body?: string }).body ?? ''}`).toContain('今期の受付は終了しました')
      // 直後の再申請は 403 TEAM_068（API 直打ち。画面では申請ボタンが出なくなる）
      const blocked = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.X.slug })
      expect([blocked.status, blocked.body.error.code]).toEqual([403, 'TEAM_068'])
      blockedBody = blocked.body
      // AC-C11: 申請フォームでは制限中のチームは UNAVAILABLE（冷却かブロックかは区別しない）
      const form = await apiAs(w.TA, 'GET', `/api/v1/organizations/${w.X.slug}/team-application-form`)
      expect(JSON.stringify(form.body)).toContain('UNAVAILABLE')
      expect(JSON.stringify(form.body)).not.toMatch(/BLOCK|COOLDOWN/)
      // 画面: 申請ダイアログで T は「現在この組織には申請できません」として選べない（冷却・ブロックの区別なし）
      await ta.page.goto(`/organizations/${w.X.slug}`)
      await ta.page.getByTestId('team-affiliation-apply-button').click()
      await ta.page.getByTestId('apply-team-select').click()
      const unavailable = ta.page.getByRole('option', { name: `${w.T.name}（現在この組織には申請できません）` })
      await expect(unavailable).toBeVisible({ timeout: 30_000 })
      await expect(unavailable).toHaveAttribute('aria-disabled', 'true')
      await ta.page.keyboard.press('Escape')
      await ta.page.keyboard.press('Escape')
      // ブロックは restricted_until が NULL（時計を進めても解けない）
      expect(sql(`SELECT r.kind, IFNULL(r.restricted_until,'NULL') FROM team_org_affiliation_restrictions r JOIN teams t ON t.id=r.team_id JOIN organizations o ON o.id=r.organization_id WHERE t.slug='${w.T.slug}' AND o.slug='${w.X.slug}';`)).toBe('BLOCK\tNULL')

      // AC-C09 後半: XA が制限一覧から解除 → TA の申請が 201
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=restrictions`)
      await enableAdminLens(xa.page)
      const restriction = xa.page.getByTestId('restriction-row').filter({ hasText: w.T.name })
      await expect(restriction).toBeVisible({ timeout: 30_000 })
      await expect(restriction).toContainText('ブロック中')
      // 【製品欠陥の記録】制限一覧の理由欄に理由コード（REJECTED）がそのまま表示される（i18n されていない生の列挙値）。
      // 期待: 利用者向けの文言で表示する（または表示しない）。soft で記録し、以降の操作は続ける。
      await expect.soft(restriction, '制限一覧に理由コードの生値を出さない').not.toContainText('REJECTED')
      const lifted = await clickAndWait(xa.page, () => restriction.getByRole('button', { name: '解除' }).click(), 'DELETE', new RegExp(`/team-affiliation-restrictions/[^/]+$`))
      expect(lifted.status()).toBe(204)
      await expect(restriction).toHaveCount(0)
      await applyViaUi(ta.page, 201)

      // AC-B13: TA が取り下げると XA の申請一覧から消え、直後の再申請は 403、24 時間後は 201
      await ta.page.goto(`/teams/${w.T.slug}/affiliations?view=applications`)
      const mine = ta.page.getByTestId('application-row').filter({ hasText: w.X.name })
      await expect(mine).toBeVisible({ timeout: 30_000 })
      await mine.getByRole('button', { name: '取下げ' }).click()
      const withdrawn = await clickAndWait(ta.page, () => ta.page.getByTestId('application-withdraw-confirm').click(), 'DELETE', new RegExp(`/api/v1/teams/${w.T.slug}/org-applications/\\d+$`))
      expect(withdrawn.status()).toBe(204)
      await expect(ta.page.getByText('加盟申請を取り下げました')).toBeVisible()
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=applications`)
      await enableAdminLens(xa.page)
      await expect(xa.page.getByTestId('applications-empty')).toBeVisible({ timeout: 30_000 })
      const cooled = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.X.slug })
      expect([cooled.status, cooled.body.error.code]).toEqual([403, 'TEAM_068'])
      // AC-C10: 冷却中とブロック中の応答（ステータス・コード・文言）が同一
      expect(cooled.body).toEqual(blockedBody)
      sql(
        `UPDATE team_org_affiliation_restrictions r JOIN teams t ON t.id=r.team_id JOIN organizations o ON o.id=r.organization_id ` +
          `SET r.restricted_until = r.restricted_until - INTERVAL 25 HOUR WHERE t.slug='${w.T.slug}' AND o.slug='${w.X.slug}';`,
      )
      await applyViaUi(ta.page, 201)

      // AC-C08: 理由なし・ブロックなしで拒否 → 30 日以内は 403、31 日進めると 201
      await rejectViaUi(xa.page, '', false)
      const within = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.X.slug })
      expect([within.status, within.body.error.code]).toEqual([403, 'TEAM_068'])
      sql(
        `UPDATE team_org_affiliation_restrictions r JOIN teams t ON t.id=r.team_id JOIN organizations o ON o.id=r.organization_id ` +
          `SET r.restricted_until = r.restricted_until - INTERVAL 31 DAY WHERE t.slug='${w.T.slug}' AND o.slug='${w.X.slug}';`,
      )
      await applyViaUi(ta.page, 201)
      // 承認して ACTIVE に戻す（以後の除名・招待の前提）
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=applications`)
      await enableAdminLens(xa.page)
      await xa.page.getByTestId('application-row').filter({ hasText: w.T.name }).getByRole('button', { name: '承認' }).click()
      const approved = await clickAndWait(xa.page, () => xa.page.getByTestId('approve-submit').click(), 'POST', new RegExp(`/team-applications/[^/]+/approve$`))
      expect(approved.status()).toBe(200)
      expect(membershipRow(w.X.slug, w.T.slug)?.status).toBe('ACTIVE')
    } finally {
      await close(ta, xa)
    }
    // AC-C12: XA が他組織Y の制限 ID を解除しようとすると 404（X の制限 ID を Y の ADMIN が Y の slug で解除）
    const before = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.Y.slug })
    expect(before.status).toBe(403)
    sql(
      `INSERT INTO team_org_affiliation_restrictions (id, organization_id, team_id, direction, kind, reason, restricted_until, created_by, created_at, updated_at) ` +
        `SELECT UUID_TO_BIN(UUID()), o.id, t.id, 'TEAM_APPLY', 'COOLDOWN', 'REJECTED', UTC_TIMESTAMP() + INTERVAL 1 DAY, ${w.XA.id}, UTC_TIMESTAMP(), UTC_TIMESTAMP() ` +
        `FROM organizations o, teams t WHERE o.slug='${w.X.slug}' AND t.slug='${w.U.slug}';`,
    )
    const xRestrictionId = sql(`SELECT BIN_TO_UUID(r.id) FROM team_org_affiliation_restrictions r JOIN organizations o ON o.id=r.organization_id JOIN teams t ON t.id=r.team_id WHERE o.slug='${w.X.slug}' AND t.slug='${w.U.slug}' LIMIT 1;`)
    const cross = await apiAs(w.YA, 'DELETE', `/api/v1/organizations/${w.Y.slug}/team-affiliation-restrictions/${xRestrictionId}`)
    expect(cross.status, 'AC-C12').toBe(404)
    sql(`DELETE FROM team_org_affiliation_restrictions WHERE id=UUID_TO_BIN('${xRestrictionId}');`)
  })

  test('ORGAFF-07 [AC-P01/P02/P03/P04/P06/P09/P10/P11/K03] TA が権限グループで TG・TD に加盟操作権限を付与し、タブと直打ちの可否が変わる', async ({ browser }) => {
    test.setTimeout(1200_000)
    // AC-P01: 既定では TA だけが MANAGE_ORG_AFFILIATION を持つ
    const perm = async (actor: Actor) => JSON.stringify((await apiAs(actor, 'GET', `/api/v1/teams/${w.T.slug}/me/permissions`)).body)
    expect(await perm(w.TA)).toContain('MANAGE_ORG_AFFILIATION')
    expect(await perm(w.TD)).not.toContain('MANAGE_ORG_AFFILIATION')
    expect(await perm(w.TM)).not.toContain('MANAGE_ORG_AFFILIATION')
    // AC-P02 / K03: TM・TD・TG（付与前）は所属組織タブが出ず、直打ちでチームトップへ戻る。TD・TG は権限グループ画面も戻る
    for (const actor of [w.TM, w.TD, w.TG]) {
      const s = await openAs(browser, actor, `/teams/${w.T.slug}/affiliations`)
      try {
        await expect(s.page).toHaveURL(new RegExp(`/teams/${w.T.slug}$`), { timeout: 30_000 })
        await expect(s.page.getByRole('link', { name: '所属組織' })).toHaveCount(0)
      } finally {
        await close(s)
      }
    }
    for (const actor of [w.TD, w.TG]) {
      const s = await openAs(browser, actor, `/teams/${w.T.slug}/permission-groups`)
      try {
        await expect(s.page).toHaveURL(new RegExp(`/teams/${w.T.slug}$`), { timeout: 30_000 })
      } finally {
        await close(s)
      }
    }
    for (const actor of [w.TM, w.TD]) {
      const r = await apiAs(actor, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.Y.slug })
      expect(r.status, `AC-P02 ${actor.label}`).toBe(403)
    }
    const permissionId = Number(sql(`SELECT id FROM permissions WHERE name='MANAGE_ORG_AFFILIATION';`))
    // AC-P10: TD が同権限を含む権限グループを作ろうとすると 403
    const tdCreate = await apiAs(w.TD, 'POST', `/api/v1/teams/${w.T.slug}/permission-groups`, { name: `TD自己昇格 ${RUN}`, permissionIds: [permissionId], targetRole: 'DEPUTY_ADMIN' })
    expect(tdCreate.status, 'AC-P10').toBe(403)

    // AC-P03 / P04: TA が画面で権限グループを2つ作り、メンバー一覧から TG（MEMBER）と TD（DEPUTY）に割り当てる
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}/permission-groups`)
    const groups: Record<string, string> = {}
    try {
      for (const [role, roleLabel, name] of [['MEMBER', 'メンバー', `加盟担当M ${RUN}`], ['DEPUTY_ADMIN', '副管理者', `加盟担当D ${RUN}`]] as const) {
        await ta.page.getByTestId('permission-group-create').click()
        await ta.page.getByTestId('pf-name').fill(name)
        await ta.page.getByTestId('pf-target-role').click()
        await ta.page.getByRole('option', { name: roleLabel, exact: true }).filter({ visible: true }).first().click()
        await ta.page.getByTestId(`pf-perm-${permissionId}`).click()
        const created = await clickAndWait(ta.page, () => ta.page.getByTestId('pf-submit').click(), 'POST', `/api/v1/teams/${w.T.slug}/permission-groups`)
        expect(created.status(), await created.text()).toBe(201)
        groups[role] = String((await created.json()).data.id)
        await expect(ta.page.getByTestId('pf-saved')).toBeVisible()
        await ta.page.getByRole('button', { name: '閉じる' }).last().click()
      }
      await ta.page.goto(`/teams/${w.T.slug}/members`)
      for (const [actor, role] of [[w.TG, 'MEMBER'], [w.TD, 'DEPUTY_ADMIN']] as const) {
        await ta.page.getByTestId(`member-permission-groups-${actor.id}`).click()
        await ta.page.getByTestId(`pg-check-${groups[role]}`).click()
        const assigned = await clickAndWait(ta.page, () => ta.page.getByTestId('pg-assign-submit').click(), 'PUT', `/api/v1/teams/${w.T.slug}/members/${actor.id}/permission-groups`)
        expect(assigned.status(), await assigned.text()).toBe(200)
        await expect(ta.page.getByText('権限グループを更新しました').first()).toBeVisible()
      }
    } finally {
      await close(ta)
    }
    expect(await perm(w.TG)).toContain('MANAGE_ORG_AFFILIATION')
    expect(await perm(w.TD)).toContain('MANAGE_ORG_AFFILIATION')
    expect(await perm(w.TM)).not.toContain('MANAGE_ORG_AFFILIATION')
    // 付与された TG は「所属組織」タブから画面に入れる
    const tg = await openAs(browser, w.TG, `/teams/${w.T.slug}`)
    try {
      await tg.page.getByRole('link', { name: '所属組織' }).first().click()
      await expect(tg.page).toHaveURL(new RegExp(`/teams/${w.T.slug}/affiliations$`))
      await expect(tg.page.getByTestId('org-row').filter({ hasText: w.X.name })).toBeVisible({ timeout: 30_000 })
    } finally {
      await close(tg)
    }
    // AC-P06: TG は権限グループの作成 403。AC-P11: TD は同権限を含むグループを TM に割り当てられない
    const tgCreate = await apiAs(w.TG, 'POST', `/api/v1/teams/${w.T.slug}/permission-groups`, { name: `TG作成 ${RUN}`, permissionIds: [permissionId], targetRole: 'MEMBER' })
    expect(tgCreate.status, 'AC-P06').toBe(403)
    const tdAssign = await apiAs(w.TD, 'PUT', `/api/v1/teams/${w.T.slug}/members/${w.TM.id}/permission-groups`, { groupIds: [Number(groups.MEMBER)] })
    expect(tdAssign.status, 'AC-P11').toBe(403)
    expect(await perm(w.TM)).not.toContain('MANAGE_ORG_AFFILIATION')
    // AC-P09: MEMBER 既定3権限の画面に加盟操作は出ない
    const xa = await openAs(browser, w.TA, `/teams/${w.T.slug}/members`)
    try {
      await xa.page.goto(`/admin/member-permissions?scopeType=TEAM&scopeId=${w.T.numericId}`)
      await expect(xa.page.locator('body')).not.toContainText('組織への加盟操作')
    } finally {
      await close(xa)
    }
  })

  test('ORGAFF-08 [AC-E02/E10/D01/D02/L06/D06/P07] XA が T を除名（TA・TG に通知）→画面から再招待→TA が通知から招待一覧に着地して承諾', async ({ browser }) => {
    test.setTimeout(1200_000)
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}/member-teams`)
    try {
      await enableAdminLens(xa.page)
      const remove = xa.page.getByTestId(`team-remove-${w.T.slug}`)
      await expect(remove).toBeVisible({ timeout: 30_000 })
      await remove.click()
      const removed = await clickAndWait(
        xa.page,
        () => xa.page.getByRole('alertdialog').getByRole('button', { name: '除名' }).click(),
        'DELETE',
        `/api/v1/organizations/${w.X.slug}/teams/${w.T.slug}`,
      )
      expect(removed.status()).toBe(204)
      await expect(xa.page.getByText('チームを除名しました').first()).toBeVisible()
      expect(membershipRow(w.X.slug, w.T.slug)).toBeNull()
      // AC-E10: TA と TG に除名通知（リンクは /teams/T/affiliations）、TM と XA には届かない
      for (const actor of [w.TA, w.TG]) {
        const n = await waitNotification(actor, 'TEAM_ORG_MEMBERSHIP_REMOVED', w.T.slug)
        expect(n.actionUrl).toBe(`/teams/${w.T.slug}/affiliations`)
      }
      await new Promise((r) => setTimeout(r, FANOUT_WAIT_MS))
      for (const actor of [w.TM, w.XA]) {
        expect((await notificationsOf(actor)).some((x) => typeOf(x) === 'TEAM_ORG_MEMBERSHIP_REMOVED' && `${x.actionUrl}`.includes(w.T.slug)), actor.label).toBe(false)
      }

      // AC-D01: 画面から T を招待
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=invites`)
      await enableAdminLens(xa.page)
      await xa.page.getByTestId('invite-open').click()
      await xa.page.getByTestId('invite-search-input').fill(w.T.name)
      await xa.page.getByTestId('invite-search-submit').click()
      await xa.page.getByTestId(`invite-result-${w.T.slug}`).click()
      await xa.page.getByTestId('invite-message').fill('実機E2Eからの招待です')
      const invited = await clickAndWait(xa.page, () => xa.page.getByTestId('invite-submit').click(), 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`)
      expect(invited.status(), await invited.text()).toBe(201)
      await expect(xa.page.getByText('招待を送りました')).toBeVisible()
      await expect(xa.page.getByTestId('invite-row').filter({ hasText: w.T.name })).toBeVisible()
    } finally {
      await close(xa)
    }
    // AC-D06: TM は承諾できない（403）
    const inviteId = membershipRow(w.X.slug, w.T.slug)!.id
    const tmAccept = await apiAs(w.TM, 'POST', `/api/v1/teams/${w.T.slug}/org-invites/${inviteId}/accept`)
    expect(tmAccept.status, 'AC-D06').toBe(403)
    // AC-L06: TA は通知ベルの招待通知から ?view=invites に着地して承諾（TG にも届く）
    const n = await waitNotification(w.TA, 'TEAM_ORG_INVITE_RECEIVED', w.T.slug)
    expect(n.actionUrl).toBe(`/teams/${w.T.slug}/affiliations?view=invites`)
    await waitNotification(w.TG, 'TEAM_ORG_INVITE_RECEIVED', w.T.slug)
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}`)
    try {
      await clickNotificationFromBell(ta.page, n.id)
      await expect(ta.page).toHaveURL(new RegExp(`/teams/${w.T.slug}/affiliations\\?view=invites`), { timeout: 30_000 })
      const row = ta.page.getByTestId('invite-row').filter({ hasText: w.X.name })
      await expect(row).toContainText('実機E2Eからの招待です', { timeout: 30_000 })
      const accepted = await clickAndWait(ta.page, () => row.getByRole('button', { name: '承諾' }).click(), 'POST', new RegExp(`/org-invites/\\d+/accept$`))
      expect(accepted.status()).toBe(200)
      await expect(ta.page.getByText('招待を承諾しました。組織に加盟しました').first()).toBeVisible()
      await ta.page.getByTestId('view-tab-organizations').click()
      await expect(ta.page.getByTestId('org-row').filter({ hasText: w.X.name })).toBeVisible({ timeout: 30_000 })
    } finally {
      await close(ta)
    }
    expect(membershipRow(w.X.slug, w.T.slug)?.status).toBe('ACTIVE')
  })

  test('ORGAFF-09 [AC-D03/D04/D05] TA が招待を辞退（今後受け付けない）→XA の再招待は失敗→TA が招待の制限を解除→再招待→取消→直後の再招待は 403・24 時間後は 201', async ({ browser }) => {
    test.setTimeout(1200_000)
    await must(w.XA, 'DELETE', `/api/v1/organizations/${w.X.slug}/teams/${w.T.slug}`)
    const inviteViaUi = async (page: Page, expected: number) => {
      await page.goto(`/organizations/${w.X.slug}/member-teams?view=invites`)
      await enableAdminLens(page)
      await page.getByTestId('invite-open').click()
      await page.getByTestId('invite-search-input').fill(w.T.name)
      await page.getByTestId('invite-search-submit').click()
      await page.getByTestId(`invite-result-${w.T.slug}`).click()
      const res = await clickAndWait(page, () => page.getByTestId('invite-submit').click(), 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`)
      expect(res.status(), await res.text()).toBe(expected)
      return res
    }
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}`)
    try {
      await inviteViaUi(xa.page, 201)
      await ta.page.goto(`/teams/${w.T.slug}/affiliations?view=invites`)
      const row = ta.page.getByTestId('invite-row').filter({ hasText: w.X.name })
      await expect(row).toBeVisible({ timeout: 30_000 })
      await row.getByRole('button', { name: '辞退' }).click()
      await ta.page.getByTestId('invite-decline-block').click()
      const declined = await clickAndWait(ta.page, () => ta.page.getByTestId('invite-decline-confirm').click(), 'POST', new RegExp(`/org-invites/\\d+/reject$`))
      expect(declined.status()).toBe(200)
      expect(declined.request().postDataJSON()).toMatchObject({ block: true })
      await expect(ta.page.getByText('招待を辞退しました').first()).toBeVisible()
      // AC-D03: 再招待は 403 TEAM_068、画面にエラー
      const denied = await inviteViaUi(xa.page, 403)
      expect((await denied.json()).error.code).toBe('TEAM_068')
      await expect(xa.page.getByTestId('invite-error')).toBeVisible()
      // AC-D04: TA が招待の制限一覧から解除
      await ta.page.goto(`/teams/${w.T.slug}/affiliations?view=restrictions`)
      const restriction = ta.page.getByTestId('restriction-row').filter({ hasText: w.X.name })
      await expect(restriction).toContainText('無期限（今後受け付けない）', { timeout: 30_000 })
      const lifted = await clickAndWait(ta.page, () => restriction.getByRole('button', { name: '解除' }).click(), 'DELETE', new RegExp(`/org-affiliation-restrictions/[^/]+$`))
      expect(lifted.status()).toBe(204)
      await expect(ta.page.getByText('制限を解除しました').first()).toBeVisible()
      await inviteViaUi(xa.page, 201)
      // AC-D05: XA が取り消すと TA の受信一覧から消える
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=invites`)
      await enableAdminLens(xa.page)
      await xa.page.getByTestId(`invite-cancel-${w.T.slug}`).click()
      const cancelled = await clickAndWait(xa.page, () => xa.page.getByTestId('invite-cancel-confirm').click(), 'DELETE', `/api/v1/organizations/${w.X.slug}/team-invites/${w.T.slug}`)
      expect(cancelled.status()).toBe(204)
      await expect(xa.page.getByText('招待を取り消しました').first()).toBeVisible()
      await ta.page.goto(`/teams/${w.T.slug}/affiliations?view=invites`)
      await expect(ta.page.getByTestId('invites-empty')).toBeVisible({ timeout: 30_000 })
    } finally {
      await close(xa, ta)
    }
    // AC-D05 後半: 取消直後の再招待は画面でも 403 TEAM_068（エラー表示）。時計を 25 時間進めると画面から再招待でき、
    // TA の受信一覧に出る
    const xa2 = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const ta2 = await openAs(browser, w.TA, `/teams/${w.T.slug}`)
    try {
      const again = await inviteViaUi(xa2.page, 403)
      expect((await again.json()).error.code).toBe('TEAM_068')
      await expect(xa2.page.getByTestId('invite-error')).toBeVisible()
      sql(
        `UPDATE team_org_affiliation_restrictions r JOIN teams t ON t.id=r.team_id JOIN organizations o ON o.id=r.organization_id ` +
          `SET r.restricted_until = r.restricted_until - INTERVAL 25 HOUR WHERE t.slug='${w.T.slug}' AND o.slug='${w.X.slug}' AND r.restricted_until IS NOT NULL;`,
      )
      await inviteViaUi(xa2.page, 201)
      await expect(xa2.page.getByText('招待を送りました').first()).toBeVisible()
      await expect(xa2.page.getByTestId('invite-row').filter({ hasText: w.T.name })).toBeVisible()
      await ta2.page.goto(`/teams/${w.T.slug}/affiliations?view=invites`)
      await expect(ta2.page.getByTestId('invite-row').filter({ hasText: w.X.name }), 'AC-D05 24時間後に TA の受信一覧に出る').toBeVisible({
        timeout: 30_000,
      })
    } finally {
      await close(xa2, ta2)
    }
  })

  test('ORGAFF-10 [AC-E04/E06/B16] 60 日応答の無い申請は期限切れバッチで消え期限切れ通知。加盟バッチ3本は trigger?sync=true で 200（画面なし・API）', async () => {
    test.setTimeout(600_000)
    const list = await apiAs(systemAdmin, 'GET', '/api/v1/system-admin/batch')
    expect(list.status).toBe(200)
    for (const name of ['team-org-pending-expiry', 'team-org-group-orphan-repair', 'team-org-lifecycle-cleanup']) {
      expect(JSON.stringify(list.body), name).toContain(name)
    }
    // 前提: U（Y 加盟）から X へ申請（AC-B16: 他組織への加盟は申請を妨げない）
    const applied = await apiAs(w.UA, 'POST', `/api/v1/teams/${w.U.slug}/org-applications`, { organizationSlug: w.X.slug })
    expect(applied.status, `AC-B16 ${JSON.stringify(applied.body)}`).toBe(201)
    const row = membershipRow(w.X.slug, w.U.slug)!
    sql(`UPDATE team_org_memberships SET invited_at = invited_at - INTERVAL 61 DAY WHERE id=${row.id};`)
    for (const name of ['team-org-pending-expiry', 'team-org-group-orphan-repair', 'team-org-lifecycle-cleanup']) {
      const r = await triggerBatch(name)
      expect(r.status, `${name} ${JSON.stringify(r.body)}`).toBe(200)
    }
    expect(membershipRow(w.X.slug, w.U.slug)).toBeNull()
    await waitNotification(w.UA, 'TEAM_ORG_PENDING_EXPIRED', w.U.slug)
  })

  test('ORGAFF-11 [API 契約: AC-B02/B03/B04/B05/B06/B07/B11/B15/C05/C06/D07/D09/D10/E03/K04/P08/A12/A13/N03]', async () => {
    test.setTimeout(600_000)
    const apply = (actor: Actor, team: string, body: Record<string, unknown>) => apiAs(actor, 'POST', `/api/v1/teams/${team}/org-applications`, body)
    // B02 / B03: 受付 off の Y へ申請・申請フォームは 403 TEAM_064
    const b02 = await apply(w.TA, w.T.slug, { organizationSlug: w.Y.slug })
    expect([b02.status, b02.body.error?.code], 'B02').toEqual([403, 'TEAM_064'])
    const b03 = await apiAs(w.TA, 'GET', `/api/v1/organizations/${w.Y.slug}/team-application-form`)
    expect([b03.status, b03.body.error?.code], 'B03').toEqual([403, 'TEAM_064'])
    // B06: 加盟済みのチームで申請すると 409 TEAM_065（前提: 招待→承諾で ACTIVE のチームを用意）
    const joined = await createTeam(w.UA, `実機加盟済 ${RUN}`)
    await affiliate(w.XA, w.X.slug, w.UA, joined.slug)
    const b06 = await apply(w.UA, joined.slug, { organizationSlug: w.X.slug })
    expect([b06.status, b06.body.error?.code], 'B06').toEqual([409, 'TEAM_065'])
    await archiveScope('teams', w.UA, joined.slug)
    const V = await createTeam(w.UA, `実機V ${RUN}`)
    const tm = await apply(w.TM, w.T.slug, { organizationSlug: w.X.slug })
    expect(tm.status, 'B04 TM').toBe(403)
    const b15 = await apply(w.UA, V.slug, { organizationSlug: w.X.slug, message: 'あ'.repeat(501) })
    expect(b15.status, 'B15 501').toBe(400)
    const b11 = await apply(w.UA, V.slug, { organizationSlug: w.X.slug, groupId: '00000000-0000-7000-8000-000000000000' })
    expect([b11.status, b11.body.error?.code], 'B11').toEqual([400, 'TEAM_072'])
    const first = await apply(w.UA, V.slug, { organizationSlug: w.X.slug, message: 'あ'.repeat(500) })
    expect(first.status, 'B15 500').toBe(201)
    const dup = await apply(w.UA, V.slug, { organizationSlug: w.X.slug })
    expect([dup.status, dup.body.error?.code], 'B07').toEqual([409, 'TEAM_066'])
    const vRow = membershipRow(w.X.slug, V.slug)!
    for (const actor of [w.XD, w.XM, w.YA]) {
      const r = await apiAs(actor, 'POST', `/api/v1/organizations/${w.X.slug}/team-applications/${vRow.id}/approve`, { overrideGroup: false })
      expect(r.status, `C05/P08 ${actor.label}`).toBe(403)
    }
    const c06 = await apiAs(w.YA, 'POST', `/api/v1/organizations/${w.Y.slug}/team-applications/${vRow.id}/approve`, { overrideGroup: false })
    expect([c06.status, c06.body.error?.code], 'C06').toEqual([404, 'TEAM_070'])
    const missing = await apiAs(w.YA, 'POST', `/api/v1/organizations/${w.Y.slug}/team-applications/999999999/approve`, { overrideGroup: false })
    expect(missing.body, 'C06 = 不在と同じ').toEqual(c06.body)
    // D09: 非公開チーム P の招待は存在しない slug と同じ応答
    const d09 = await apiAs(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: w.P.slug })
    const none = await apiAs(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: `nonexistent-${RUN}` })
    expect([d09.status, d09.body], 'D09').toEqual([none.status, none.body])
    expect(d09.status).toBe(404)
    // D10: グループ機能 off の X で groupId 付き招待は 400 TEAM_072
    const d10 = await apiAs(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: w.U.slug, groupId: '00000000-0000-7000-8000-000000000000' })
    expect([d10.status, d10.body.error?.code], 'D10').toEqual([400, 'TEAM_072'])
    // D07: U の ADMIN が V 宛て招待の ID を自チーム U の slug で承諾 → 404 TEAM_070
    const W = await createTeam(w.UA, `実機W ${RUN}`)
    await must(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: W.slug })
    const wInvite = membershipRow(w.X.slug, W.slug)!.id
    const d07 = await apiAs(w.UA, 'POST', `/api/v1/teams/${w.U.slug}/org-invites/${wInvite}/accept`)
    expect([d07.status, d07.body.error?.code], 'D07').toEqual([404, 'TEAM_070'])
    // B08: 組織X から招待が届いている W から X へ申請すると 409 TEAM_066
    const b08 = await apply(w.UA, W.slug, { organizationSlug: w.X.slug })
    expect([b08.status, b08.body.error?.code], 'B08').toEqual([409, 'TEAM_066'])
    // B09: PENDING 10 件までは成功、11 件目は 422 TEAM_069（V は X へ 1 件申請済み）
    const extraOrgs: Scope[] = []
    for (let i = 0; i < 10; i++) {
      const org = await createOrg(w.XA, `実機上限組織${i} ${RUN}`)
      await putSettings(w.XA, org.slug, { teamApplicationEnabled: true, teamGroupsEnabled: false, applicationGroupMode: 'OFF' })
      extraOrgs.push(org)
    }
    for (let i = 0; i < 9; i++) {
      // 申請の 10 件/時/利用者のレート制限（§10.10）は B09 の対象外なので、前提としてカウンタを戻す
      resetUserRateLimit('team-org-affiliation:apply', w.UA)
      const r = await apply(w.UA, V.slug, { organizationSlug: extraOrgs[i].slug })
      expect(r.status, `B09 ${i + 2} 件目`).toBe(201)
    }
    resetUserRateLimit('team-org-affiliation:apply', w.UA)
    const b09 = await apply(w.UA, V.slug, { organizationSlug: extraOrgs[9].slug })
    expect([b09.status, b09.body.error?.code], 'B09 11 件目').toEqual([422, 'TEAM_069'])
    // E03: 未加盟の組み合わせで離脱・除名
    const e03a = await apiAs(w.UA, 'DELETE', `/api/v1/teams/${w.U.slug}/organizations/${w.X.slug}`)
    expect([e03a.status, e03a.body.error?.code], 'E03 離脱').toEqual([404, 'TEAM_070'])
    const e03b = await apiAs(w.XA, 'DELETE', `/api/v1/organizations/${w.X.slug}/teams/${w.U.slug}`)
    expect([e03b.status, e03b.body.error?.code], 'E03 除名').toEqual([404, 'TEAM_070'])
    // K04: 他組織の membershipId を総当たりしても全て同じ 404 TEAM_070（存在・不在を区別できない）
    const base = Number(vRow.id)
    const bodies = new Set<string>()
    for (let id = base - 25; id <= base + 25; id++) {
      const own = sql(`SELECT COUNT(*) FROM team_org_memberships m JOIN organizations o ON o.id=m.organization_id WHERE m.id=${id} AND o.slug='${w.Y.slug}';`)
      if (own !== '0') continue
      const r = await apiAs(w.YA, 'POST', `/api/v1/organizations/${w.Y.slug}/team-applications/${id}/reject`, { block: false })
      bodies.add(`${r.status} ${JSON.stringify(r.body)}`)
    }
    expect([...bodies], 'K04').toHaveLength(1)
    // A12: eligibility は不在・非公開・受付 off で同一の {canApply:false}
    const PX = await createOrg(w.XA, `実機非公開組織 ${RUN}`, 'PRIVATE')
    await putSettings(w.XA, PX.slug, { teamApplicationEnabled: true, teamGroupsEnabled: false, applicationGroupMode: 'OFF' })
    const elig = async (slug: string) => apiAs(w.UA, 'GET', `/api/v1/me/org-affiliation-eligibility?organizationSlug=${slug}`)
    const e1 = await elig(`nonexistent-${RUN}`)
    const e2 = await elig(PX.slug)
    const e3 = await elig(w.Y.slug)
    expect([e1.status, e2.status, e3.status], 'A12').toEqual([200, 200, 200])
    expect(JSON.stringify(e1.body), 'A12').toContain('"canApply":false')
    expect(e2.body, 'A12 非公開').toEqual(e1.body)
    expect(e3.body, 'A12 受付off').toEqual(e1.body)
    // B05: visibility 上見えない受付 on の組織への申請は存在しない slug と同じ 404
    const b05 = await apply(w.UA, V.slug, { organizationSlug: PX.slug })
    const b05none = await apply(w.UA, V.slug, { organizationSlug: `nonexistent-${RUN}` })
    expect([b05.status, b05.body], 'B05').toEqual([b05none.status, b05none.body])
    expect(b05.status).toBe(404)
    // A13: 公開 API は公開組織を返し、非公開・不在は同じ応答
    const pub = await apiAs(null, 'GET', `/api/v1/public/organizations/${w.Y.slug}`)
    expect(pub.status, 'A13').toBe(200)
    expect(JSON.stringify(pub.body), 'A13').toContain('"acceptingTeamApplications":false')
    const pubPrivate = await apiAs(null, 'GET', `/api/v1/public/organizations/${PX.slug}`)
    const pubNone = await apiAs(null, 'GET', `/api/v1/public/organizations/nonexistent-${RUN}`)
    expect([pubPrivate.status, pubPrivate.body], 'A13').toEqual([pubNone.status, pubNone.body])
    // N03: /me/teams の U の要素に X と Y の両方（U を X にも加盟させる）
    await must(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: w.U.slug })
    await must(w.UA, 'POST', `/api/v1/teams/${w.U.slug}/org-invites/${membershipRow(w.X.slug, w.U.slug)!.id}/accept`)
    const meTeams = JSON.stringify((await apiAs(w.UA, 'GET', '/api/v1/me/teams')).body)
    expect(meTeams, 'N03 X').toContain(w.X.name)
    expect(meTeams, 'N03 Y').toContain(w.Y.name)
    // C04 (a)(b): U は X・Y の両方の加盟チーム一覧に出て、U の所属組織に X と Y が出る
    const xTeams = JSON.stringify((await apiAs(w.XA, 'GET', `/api/v1/organizations/${w.X.slug}/teams`)).body)
    const yTeams = JSON.stringify((await apiAs(w.YA, 'GET', `/api/v1/organizations/${w.Y.slug}/teams`)).body)
    expect(xTeams, 'C04 a X').toContain(w.U.slug)
    expect(yTeams, 'C04 a Y').toContain(w.U.slug)
    const uOrgs = JSON.stringify((await apiAs(w.UA, 'GET', `/api/v1/teams/${w.U.slug}/organizations`)).body)
    expect(uOrgs, 'C04 b').toContain(w.X.slug)
    expect(uOrgs, 'C04 b').toContain(w.Y.slug)
    // N14: /me/teams の代表親組織は 10 回呼んでも同じ
    const reps = new Set<string>()
    for (let i = 0; i < 10; i++) {
      const teams = (await apiAs(w.UA, 'GET', '/api/v1/me/teams')).body.data as Array<{ slug?: string; organizationId?: unknown; organization_id?: unknown }>
      const u = teams.find((t) => t.slug === w.U.slug) as Record<string, unknown> | undefined
      reps.add(JSON.stringify(u?.organizationId ?? u?.organization_id ?? null))
    }
    expect([...reps], 'N14').toHaveLength(1)
    // C14: 申請中のチーム V を削除すると、X の申請行が消え XA に取消の通知が届く
    expect(membershipRow(w.X.slug, V.slug)?.status).toBe('PENDING')
    for (const team of [V, W]) await archiveScope('teams', w.UA, team.slug)
    await expect.poll(() => membershipRow(w.X.slug, V.slug), { timeout: 30_000 }).toBeNull()
    await waitNotification(w.XA, 'TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM', w.X.slug)
    await archiveScope('organizations', w.XA, PX.slug)
    for (const org of extraOrgs) await archiveScope('organizations', w.XA, org.slug)
  })
  test('ORGAFF-12 [AC-P05/P12] TA が TG の権限グループ割当を画面で外すと、TG のタブが消え直打ちでチームトップへ戻り、申請 API も 403', async ({ browser }) => {
    test.setTimeout(600_000)
    const groupId = sql(
      `SELECT pg.id FROM permission_groups pg JOIN teams t ON t.id=pg.team_id WHERE t.slug='${w.T.slug}' AND pg.target_role='MEMBER' AND pg.deleted_at IS NULL ORDER BY pg.id LIMIT 1;`,
    )
    // AC-P12: TD は同権限を含む既存グループの更新（名前の変更）も 403
    const permissionId = Number(sql(`SELECT id FROM permissions WHERE name='MANAGE_ORG_AFFILIATION';`))
    const tdUpdate = await apiAs(w.TD, 'PATCH', `/api/v1/teams/${w.T.slug}/permission-groups/${groupId}`, {
      name: `改名 ${RUN}`,
      permissionIds: [permissionId],
      targetRole: 'MEMBER',
    })
    expect(tdUpdate.status, 'AC-P12').toBe(403)
    const ta = await openAs(browser, w.TA, `/teams/${w.T.slug}/members`)
    try {
      await ta.page.getByTestId(`member-permission-groups-${w.TG.id}`).click()
      await ta.page.getByTestId(`pg-check-${groupId}`).click()
      await expect(ta.page.getByTestId('pg-diff-remove')).toBeVisible()
      const saved = await clickAndWait(ta.page, () => ta.page.getByTestId('pg-assign-submit').click(), 'PUT', `/api/v1/teams/${w.T.slug}/members/${w.TG.id}/permission-groups`)
      expect(saved.status()).toBe(200)
    } finally {
      await close(ta)
    }
    const tg = await openAs(browser, w.TG, `/teams/${w.T.slug}/affiliations`)
    try {
      await expect(tg.page).toHaveURL(new RegExp(`/teams/${w.T.slug}$`), { timeout: 30_000 })
      await expect(tg.page.getByRole('link', { name: '所属組織' })).toHaveCount(0)
    } finally {
      await close(tg)
    }
    const r = await apiAs(w.TG, 'POST', `/api/v1/teams/${w.T.slug}/org-applications`, { organizationSlug: w.Y.slug })
    expect(r.status, 'AC-P05').toBe(403)
  })
})

test.afterAll(() => {
  // 画面が実際に叩いた API のオリジン（検証 BE を見ていることの証拠）。資産の削除は globalTeardown が行う。
  console.log(`[F0121] browser API origins ${[...browserApiOrigins].join(',')} (FE ${process.env.BASE_URL})`)
})
