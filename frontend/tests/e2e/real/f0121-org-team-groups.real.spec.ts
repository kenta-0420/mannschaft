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
  createGroup,
  createOrg,
  createTeam,
  dismissPermissionSetup,
  enableAdminLens,
  joinScope,
  must,
  openAs,
  putSettings,
  sql,
  systemAdmin,
  triggerBatch,
} from './helpers/f0121-org-groups-world'

/**
 * F01.2.1 チームグループ（作成・改名・並び替え・削除・単体/一括割当・絞り込み）の実機E2E（CMP-261011-0035）。
 *
 * 実行: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081
 *   node node_modules/playwright/cli.js test -c playwright-real.config.ts --project=chromium-real --no-deps \
 *   tests/e2e/real/f0121-org-team-groups.real.spec.ts
 *
 * API は、ログイン・前提作成（利用者・組織・チーム・加盟）・後始末・観測（DB）と、受け入れ条件が
 * 「API を呼ぶと 4xx」と定める契約（GRP-API）にだけ使う。グループと割当の操作は画面で行う。
 */

interface World {
  XA: Actor
  XD: Actor
  XM: Actor
  YA: Actor
  TA: Actor
  X: Scope
  Y: Scope
  T1: Scope
  T2: Scope
  T3: Scope
  T4: Scope
  T5: Scope
  T6: Scope
  T7: Scope
  yGroupId: string
}

const cacheDir = path.resolve('test-results')
const cacheFile = path.join(cacheDir, `f0121-groups-world-${process.ppid}.json`)
let w: World
const G = { A: `年度A ${RUN}`, B: `年度B ${RUN}`, C: `地区C ${RUN}`, C2: `地区C改 ${RUN}` }

test.describe.configure({ mode: 'default' })
test.use({ screenshot: 'only-on-failure', trace: 'off' })

test.beforeAll(async () => {
  test.setTimeout(900_000)
  if (existsSync(cacheFile)) {
    w = JSON.parse(readFileSync(cacheFile, 'utf8')) as World
    return
  }
  const XA = await createActor('XA')
  const XD = await createActor('XD')
  const XM = await createActor('XM')
  const YA = await createActor('YA')
  const TA = await createActor('TA')
  const X = await createOrg(XA, `実機G組織X ${RUN}`)
  const Y = await createOrg(YA, `実機G組織Y ${RUN}`)
  await joinScope('organizations', XA, X.slug, XD, 'DEPUTY_ADMIN')
  await joinScope('organizations', XA, X.slug, XM, 'MEMBER')
  const T1 = await createTeam(TA, `実機G一 ${RUN}`)
  const T2 = await createTeam(TA, `実機G二 ${RUN}`)
  const T3 = await createTeam(TA, `実機G三 ${RUN}`)
  // AC-G01 の前提: グループ機能 off の時代に加盟したチーム（未分類）
  for (const team of [T1, T2, T3]) await affiliate(XA, X.slug, TA, team.slug)
  await putSettings(YA, Y.slug, { teamApplicationEnabled: false, teamGroupsEnabled: true, applicationGroupMode: 'OFF' })
  const yGroupId = await createGroup(YA, Y.slug, `Yのグループ ${RUN}`)
  // 申請・招待でグループを伴う加盟を確かめる未加盟チーム
  const T4 = await createTeam(TA, `実機G四 ${RUN}`)
  const T5 = await createTeam(TA, `実機G五 ${RUN}`)
  const T6 = await createTeam(TA, `実機G六 ${RUN}`)
  const T7 = await createTeam(TA, `実機G七 ${RUN}`)
  w = { XA, XD, XM, YA, TA, X, Y, T1, T2, T3, T4, T5, T6, T7, yGroupId }
  mkdirSync(cacheDir, { recursive: true })
  writeFileSync(cacheFile, JSON.stringify(w, null, 2))
  console.log(`[F0121-G] world X=${w.X.slug} Y=${w.Y.slug} T1=${T1.slug} T2=${T2.slug} T3=${T3.slug}`)
})

function groupIdOf(orgSlug: string, name: string): string {
  return sql(
    `SELECT BIN_TO_UUID(g.id) FROM org_team_groups g JOIN organizations o ON o.id=g.organization_id ` +
      `WHERE o.slug='${orgSlug}' AND g.name='${name}' AND g.deleted_at IS NULL;`,
  )
}

function membershipGroup(teamSlug: string): string {
  return sql(
    `SELECT IFNULL(BIN_TO_UUID(m.group_id),'NULL') FROM team_org_memberships m JOIN teams t ON t.id=m.team_id ` +
      `JOIN organizations o ON o.id=m.organization_id WHERE t.slug='${teamSlug}' AND o.slug='${w.X.slug}';`,
  )
}

async function groupNamesInOrder(page: Page): Promise<string[]> {
  const rows = page.getByTestId('group-row')
  const names: string[] = []
  for (let i = 0; i < (await rows.count()); i++) names.push((await rows.nth(i).locator('p.font-medium').innerText()).replace(/\s*\d+\s*チーム\s*$/, '').trim())
  return names
}

async function openGroupsView(browserPage: Page) {
  await browserPage.goto(`/organizations/${w.X.slug}/member-teams`)
  await dismissPermissionSetup(browserPage)
  await enableAdminLens(browserPage)
  await browserPage.getByTestId('view-tab-groups').click()
  await expect(browserPage).toHaveURL(/view=groups/)
}

async function createGroupViaUi(page: Page, name: string): Promise<number> {
  await page.getByTestId('group-create-open').click()
  await page.getByTestId('group-form-name').fill(name)
  const response = await clickAndWait(
    page,
    () => page.getByTestId('group-form-submit').click(),
    'POST',
    `/api/v1/organizations/${w.X.slug}/team-groups`,
  )
  return response.status()
}

function teamRow(page: Page, team: Scope) {
  return page.locator(`[data-testid="team-row"][data-team-slug="${team.slug}"]`)
}

async function chooseInSelect(page: Page, trigger: ReturnType<Page['getByTestId']>, option: string) {
  await trigger.click()
  await page.getByRole('option', { name: option, exact: true }).filter({ visible: true }).first().click()
}

test.describe('F01.2.1 チームグループの実機E2E', () => {
  test('GRP-01 [AC-F04/L04] グループ機能 off では groups view が無効案内になり、設定から on にするとグループ管理へ到達できる', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const page = s.page
    try {
      await dismissPermissionSetup(page)
      await page.getByRole('link', { name: '所属チーム' }).first().click()
      await enableAdminLens(page)
      const disabled = await clickAndWait(
        page,
        () => page.getByTestId('view-tab-groups').click(),
        'GET',
        `/api/v1/organizations/${w.X.slug}/team-groups`,
      )
      // AC-F04: off の組織でグループ API は 409 ORG_067、画面は無効案内
      expect(disabled.status()).toBe(409)
      expect((await disabled.json()).error.code).toBe('ORG_067')
      await expect(page.getByTestId('groups-disabled-card')).toContainText('チームグループ機能は無効です')
      await page.getByTestId('groups-disabled-link').click()
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/settings/team-affiliation$`))
      await page.locator('label[for="settings-groups-enabled"]').click()
      await expect(page.locator('#settings-groups-enabled')).toBeChecked()
      const saved = await clickAndWait(
        page,
        () => page.getByTestId('settings-save').click(),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`,
      )
      expect(saved.status()).toBe(200)
      // AC-A06: グループ機能 on・生存グループ 0 件で「必ず選ばせる」を保存すると 422 ORG_070
      await chooseInSelect(page, page.getByTestId('settings-group-mode'), '必ず選ばせる')
      const required = await clickAndWait(
        page,
        () => page.getByTestId('settings-save').click(),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`,
      )
      expect(required.status()).toBe(422)
      expect((await required.json()).error.code).toBe('ORG_070')
      await expect(page.getByTestId('settings-save-error')).toBeVisible()
      await chooseInSelect(page, page.getByTestId('settings-group-mode'), '選ばせない')
      // AC-L04: 設定画面の「グループを管理」からグループ管理へ
      await page.getByTestId('manage-groups-link').click()
      await expect(page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams\\?view=groups`))
      await enableAdminLens(page)
      await expect(page.getByTestId('groups-unassigned-count')).toContainText('未分類のチーム: 3', { timeout: 30_000 })
      await expect(page.getByTestId('groups-empty')).toBeVisible()
    } finally {
      await s.context.close()
    }
  })

  test('GRP-02 [AC-F01/F02/F07] XA がグループを作成（末尾に追加）・同名は拒否・改名・並び替えし、再読込後も保たれる', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}`)
    const page = s.page
    try {
      await openGroupsView(page)
      for (const name of [G.A, G.B, G.C]) {
        expect(await createGroupViaUi(page, name), name).toBe(201)
        await expect(page.getByText('グループを作成しました').first()).toBeVisible()
        // AC-F01: 並び順の末尾に入る
        await expect.poll(async () => (await groupNamesInOrder(page)).at(-1)).toBe(name)
      }
      // AC-F02: 生存グループと同名は 409 ORG_065、ダイアログにエラー文言
      expect(await createGroupViaUi(page, G.A)).toBe(409)
      await expect(page.getByTestId('group-form-error')).toHaveAttribute('data-error-code', 'ORG_065')
      await expect(page.getByTestId('group-form-error')).toContainText('同じ名前のグループがすでにあります')
      await page.getByRole('button', { name: 'キャンセル' }).click()

      // 改名（保持更新: 説明を付けて保存→もう一度名前だけ変えても説明が残る）
      const idC = groupIdOf(w.X.slug, G.C)
      await page.getByTestId(`group-edit-${idC}`).click()
      await page.getByTestId('group-form-description').fill('地区の説明')
      const renamed1 = await clickAndWait(page, () => page.getByTestId('group-form-submit').click(), 'PATCH', `/api/v1/organizations/${w.X.slug}/team-groups/${idC}`)
      expect(renamed1.status()).toBe(200)
      await page.getByTestId(`group-edit-${idC}`).click()
      await expect(page.getByTestId('group-form-description')).toHaveValue('地区の説明')
      await page.getByTestId('group-form-name').fill(G.C2)
      const renamed2 = await clickAndWait(page, () => page.getByTestId('group-form-submit').click(), 'PATCH', `/api/v1/organizations/${w.X.slug}/team-groups/${idC}`)
      expect(renamed2.status()).toBe(200)
      await page.reload()
      await enableAdminLens(page)
      await expect(page.getByTestId('group-row').filter({ hasText: G.C2 })).toContainText('地区の説明', { timeout: 30_000 })

      // 並び替え: C改 を1つ上へ → A, C改, B。再読込後も同じ
      const reordered = await clickAndWait(page, () => page.getByTestId(`group-up-${idC}`).click(), 'PUT', `/api/v1/organizations/${w.X.slug}/team-groups/order`)
      expect(reordered.status()).toBe(200)
      await page.reload()
      await enableAdminLens(page)
      await expect.poll(() => groupNamesInOrder(page), { timeout: 30_000 }).toEqual([G.A, G.C2, G.B])
    } finally {
      await s.context.close()
    }
    // AC-F07（契約）: ID 集合が一致しない並び替えは 409 ORG_068 で順序は変わらない
    const ids = [groupIdOf(w.X.slug, G.A), groupIdOf(w.X.slug, G.C2)]
    const bad = await apiAs(w.XA, 'PUT', `/api/v1/organizations/${w.X.slug}/team-groups/order`, { groupIds: ids })
    expect(bad.status).toBe(409)
    expect(bad.body.error.code).toBe('ORG_068')
  })

  test('GRP-03 [AC-G01/G02/G03] 未分類で絞ると全件、単体割当でグループ列が変わり、一括割当で 2 チームが移る', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}/member-teams`)
    const page = s.page
    try {
      await dismissPermissionSetup(page)
      await enableAdminLens(page)
      await expect(page.getByTestId('member-teams-team-list')).toBeVisible({ timeout: 30_000 })
      // AC-G01: 既存の加盟チームは全て未分類
      await chooseInSelect(page, page.getByTestId('teamlist-filter'), '未分類')
      await expect(page.getByTestId('team-row')).toHaveCount(3)
      await chooseInSelect(page, page.getByTestId('teamlist-filter'), 'すべてのグループ')

      // AC-G02: 単体割当
      const single = await clickAndWait(
        page,
        () => chooseInSelect(page, page.getByTestId(`team-assign-${w.T1.slug}`), G.A),
        'PUT',
        `/api/v1/organizations/${w.X.slug}/teams/${w.T1.slug}/team-group`,
      )
      expect(single.status()).toBe(200)
      await expect(page.getByText('グループを変更しました').first()).toBeVisible()
      await expect(teamRow(page, w.T1).getByTestId('team-group-label')).toHaveText(G.A)

      // AC-G03: 一括割当（T2・T3 → B）
      await teamRow(page, w.T2).getByTestId(`team-select-${w.T2.slug}`).click()
      await teamRow(page, w.T3).getByTestId(`team-select-${w.T3.slug}`).click()
      await expect(page.getByTestId('bulk-selected-count')).toContainText('2 チーム選択中')
      await chooseInSelect(page, page.getByTestId('bulk-target'), G.B)
      const bulk = await clickAndWait(page, () => page.getByTestId('bulk-submit').click(), 'PUT', `/api/v1/organizations/${w.X.slug}/team-group-assignments`)
      expect(bulk.status()).toBe(200)
      expect((await bulk.json()).data.updatedCount).toBe(2)
      await expect(page.getByText('2 チームのグループを変更しました').first()).toBeVisible()
      // 永続化と絞り込み
      await page.reload()
      await enableAdminLens(page)
      await chooseInSelect(page, page.getByTestId('teamlist-filter'), G.B)
      await expect(page.getByTestId('team-row')).toHaveCount(2, { timeout: 30_000 })
      await expect(teamRow(page, w.T2).getByTestId('team-group-label')).toHaveText(G.B)
      await chooseInSelect(page, page.getByTestId('teamlist-filter'), 'すべてのグループ')
      await expect(teamRow(page, w.T1).getByTestId('team-group-label')).toHaveText(G.A)
      expect(membershipGroup(w.T1.slug)).toBe(groupIdOf(w.X.slug, G.A))
    } finally {
      await s.context.close()
    }
  })

  test('GRP-04 [AC-F05/F06/F12/G06] XM・XD・SYSTEM_ADMIN はグループ列を見られるが割当・管理の UI は出ず、API も 403。YA は 403/404', async ({ browser }) => {
    test.setTimeout(600_000)
    for (const actor of [w.XM, w.XD, systemAdmin]) {
      const s = await openAs(browser, actor, `/organizations/${w.X.slug}/member-teams`)
      try {
        await expect(teamRow(s.page, w.T1).getByTestId('team-group-label')).toHaveText(G.A, { timeout: 30_000 })
        await expect(s.page.getByTestId(`team-assign-${w.T1.slug}`), actor.label).toHaveCount(0)
        await expect(s.page.getByTestId('bulk-submit')).toHaveCount(0)
        await expect(s.page.getByTestId('member-teams-view-switcher')).toHaveCount(0)
        await expect(s.page.getByTestId(`team-remove-${w.T1.slug}`)).toHaveCount(0)
        // groups view 直打ちも一覧へ戻る
        await s.page.goto(`/organizations/${w.X.slug}/member-teams?view=groups`)
        await expect(s.page).toHaveURL(new RegExp(`/organizations/${w.X.slug}/member-teams$`), { timeout: 30_000 })
        await expect(s.page.getByTestId('group-create-open')).toHaveCount(0)
      } finally {
        await s.context.close()
      }
    }
    const idA = groupIdOf(w.X.slug, G.A)
    const base = `/api/v1/organizations/${w.X.slug}`
    // AC-F05: XM は一覧 200、作成・変更・削除 403
    expect((await apiAs(w.XM, 'GET', `${base}/team-groups`)).status).toBe(200)
    // AC-F12: SYSTEM_ADMIN は 6 経路すべて 403
    for (const actor of [w.XM, w.XD, systemAdmin]) {
      const results = [
        await apiAs(actor, 'POST', `${base}/team-groups`, { name: `不正 ${RUN}` }),
        await apiAs(actor, 'PATCH', `${base}/team-groups/${idA}`, { name: `不正改 ${RUN}` }),
        await apiAs(actor, 'DELETE', `${base}/team-groups/${idA}`),
        await apiAs(actor, 'PUT', `${base}/team-groups/order`, { groupIds: [idA] }),
        await apiAs(actor, 'PUT', `${base}/teams/${w.T1.slug}/team-group`, { groupId: null }),
        await apiAs(actor, 'PUT', `${base}/team-group-assignments`, { groupId: null, teamSlugs: [w.T1.slug] }),
      ]
      expect(results.map((r) => r.status), actor.label).toEqual([403, 403, 403, 403, 403, 403])
    }
    expect(membershipGroup(w.T1.slug)).toBe(idA)
    // AC-F06: YA は X の一覧 403、X のグループ ID を Y の slug で PATCH すると 404 ORG_064
    expect((await apiAs(w.YA, 'GET', `${base}/team-groups`)).status).toBe(403)
    const cross = await apiAs(w.YA, 'PATCH', `/api/v1/organizations/${w.Y.slug}/team-groups/${idA}`, { name: '越境' })
    expect(cross.status).toBe(404)
    expect(cross.body.error.code).toBe('ORG_064')
  })

  test('GRP-05 [AC-G04/G05/G07/K06] 一括割当・単体割当の契約（未加盟 slug・501 件・空配列・他組織グループ）', async () => {
    const base = `/api/v1/organizations/${w.X.slug}`
    const idA = groupIdOf(w.X.slug, G.A)
    const notMember = await apiAs(w.XA, 'PUT', `${base}/team-group-assignments`, { groupId: idA, teamSlugs: [w.T2.slug, 'no-such-team-zz'] })
    expect(notMember.status).toBe(400)
    expect(notMember.body.error.code).toBe('ORG_069')
    expect(JSON.stringify(notMember.body)).toContain('no-such-team-zz')
    expect(membershipGroup(w.T2.slug)).toBe(groupIdOf(w.X.slug, G.B))
    const tooMany = await apiAs(w.XA, 'PUT', `${base}/team-group-assignments`, {
      groupId: idA,
      teamSlugs: Array.from({ length: 501 }, (_, i) => `slug-${i}`),
    })
    expect(tooMany.status).toBe(400)
    const empty = await apiAs(w.XA, 'PUT', `${base}/team-group-assignments`, { groupId: idA, teamSlugs: [] })
    expect(empty.status).toBe(400)
    expect(JSON.stringify(empty.body)).not.toContain('updatedCount')
    const foreignBulk = await apiAs(w.XA, 'PUT', `${base}/team-group-assignments`, { groupId: w.yGroupId, teamSlugs: [w.T1.slug] })
    expect(foreignBulk.status).toBe(404)
    expect(foreignBulk.body.error.code).toBe('ORG_064')
    const foreignOne = await apiAs(w.XA, 'PUT', `${base}/teams/${w.T1.slug}/team-group`, { groupId: w.yGroupId })
    expect(foreignOne.status).toBe(404)
    expect(foreignOne.body.error.code).toBe('ORG_064')
    expect(membershipGroup(w.T1.slug)).toBe(idA)
  })

  test('GRP-06 [AC-F10] チームT1 の ADMIN は所属組織画面で自チームのグループ名だけを見られ、組織のグループ一覧は取れない', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.TA, `/teams/${w.T1.slug}/affiliations`)
    try {
      const row = s.page.getByTestId('org-row').filter({ hasText: w.X.name })
      await expect(row).toContainText(G.A, { timeout: 30_000 })
      await expect(row).not.toContainText(G.B)
    } finally {
      await s.context.close()
    }
    const orgs = await apiAs(w.TA, 'GET', `/api/v1/teams/${w.T1.slug}/organizations`)
    expect(JSON.stringify(orgs.body)).toContain(G.A)
    expect(JSON.stringify(orgs.body)).not.toContain(G.B)
    expect((await apiAs(w.TA, 'GET', `/api/v1/organizations/${w.X.slug}/team-groups`)).status).toBe(403)
  })

  test('GRP-07 [AC-F08/F11] 2 チームが所属するグループを削除すると即座に未分類表示、リスナー後に group_id が NULL。孤児は修復バッチで NULL', async ({ browser }) => {
    test.setTimeout(600_000)
    const idB = groupIdOf(w.X.slug, G.B)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}/member-teams?view=groups`)
    const page = s.page
    try {
      await dismissPermissionSetup(page)
      await enableAdminLens(page)
      await page.getByTestId(`group-delete-${idB}`).click()
      await expect(page.getByTestId('group-delete-message')).toContainText('所属している 2 チームは未分類に戻ります')
      const deleted = await clickAndWait(page, () => page.getByTestId('group-delete-confirm').click(), 'DELETE', `/api/v1/organizations/${w.X.slug}/team-groups/${idB}`)
      expect(deleted.status()).toBe(204)
      await expect(page.getByTestId('group-row').filter({ hasText: G.B })).toHaveCount(0)
      await page.getByTestId('view-tab-teams').click()
      await expect(teamRow(page, w.T2).getByTestId('team-group-label')).toHaveText('未分類', { timeout: 30_000 })
      await expect(teamRow(page, w.T3).getByTestId('team-group-label')).toHaveText('未分類')
      await page.getByTestId('bulk-target').click()
      await expect(page.getByRole('option', { name: G.B, exact: true }).filter({ visible: true })).toHaveCount(0)
      await page.keyboard.press('Escape')
    } finally {
      await s.context.close()
    }
    await expect.poll(() => membershipGroup(w.T2.slug), { timeout: 30_000 }).toBe('NULL')
    expect(membershipGroup(w.T3.slug)).toBe('NULL')
    // AC-F02 後半: 削除済みと同名なら作れる（UI）
    const s2 = await openAs(browser, w.XA, `/organizations/${w.X.slug}/member-teams?view=groups`)
    try {
      await dismissPermissionSetup(s2.page)
      await enableAdminLens(s2.page)
      expect(await createGroupViaUi(s2.page, G.B)).toBe(201)
    } finally {
      await s2.context.close()
    }
    // AC-F11: 付け替え漏れ（削除済みグループを指したまま）を作り、修復バッチで NULL になる
    sql(
      `UPDATE team_org_memberships m JOIN teams t ON t.id=m.team_id SET m.group_id=UUID_TO_BIN('${idB}') WHERE t.slug='${w.T3.slug}';`,
    )
    expect(membershipGroup(w.T3.slug)).toBe(idB)
    const repaired = await triggerBatch('team-org-group-orphan-repair')
    expect(repaired.status, JSON.stringify(repaired.body)).toBe(200)
    expect(membershipGroup(w.T3.slug)).toBe('NULL')
  })

  test('GRP-08 [AC-F09] グループ機能を off→on に戻すと、グループと割当がそのまま戻る', async ({ browser }) => {
    test.setTimeout(600_000)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}/settings/team-affiliation`)
    const page = s.page
    try {
      const toggle = page.locator('#settings-groups-enabled')
      await expect(toggle).toBeChecked({ timeout: 30_000 })
      await page.locator('label[for="settings-groups-enabled"]').click()
      const off = await clickAndWait(page, () => page.getByTestId('settings-save').click(), 'PUT', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
      expect(off.status()).toBe(200)
      await page.goto(`/organizations/${w.X.slug}/member-teams`)
      await dismissPermissionSetup(page)
      await expect(page.getByText(w.T1.name).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByTestId('team-group-label')).toHaveCount(0)
      await page.goto(`/organizations/${w.X.slug}/settings/team-affiliation`)
      await expect(toggle).not.toBeChecked({ timeout: 30_000 })
      await page.locator('label[for="settings-groups-enabled"]').click()
      const on = await clickAndWait(page, () => page.getByTestId('settings-save').click(), 'PUT', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
      expect(on.status()).toBe(200)
      await page.goto(`/organizations/${w.X.slug}/member-teams`)
      await expect(teamRow(page, w.T1).getByTestId('team-group-label')).toHaveText(G.A, { timeout: 30_000 })
    } finally {
      await s.context.close()
    }
  })

  test('GRP-10 [AC-B10/B12/C01/C02/C03/D10/D11] グループ選択必須の組織で、画面の申請はグループ未選択だと送れず、承認時に希望のまま・変更・未分類を選べる。招待のグループ削除後の承諾は未分類', async ({ browser }) => {
    test.setTimeout(1200_000)
    const idA = groupIdOf(w.X.slug, G.A)
    const idC2 = groupIdOf(w.X.slug, G.C2)
    // XA が設定画面で受付 on・「必ず選ばせる」にする
    const xa = await openAs(browser, w.XA, `/organizations/${w.X.slug}/settings/team-affiliation`)
    const ta = await openAs(browser, w.TA, `/organizations/${w.X.slug}`)
    try {
      await expect(xa.page.locator('#settings-groups-enabled')).toBeChecked({ timeout: 30_000 })
      if (!(await xa.page.locator('#settings-application-enabled').isChecked())) {
        await xa.page.locator('label[for="settings-application-enabled"]').click()
      }
      await chooseInSelect(xa.page, xa.page.getByTestId('settings-group-mode'), '必ず選ばせる')
      const saved = await clickAndWait(xa.page, () => xa.page.getByTestId('settings-save').click(), 'PUT', `/api/v1/organizations/${w.X.slug}/team-affiliation-settings`)
      expect(saved.status()).toBe(200)

      // AC-B10: 画面ではグループ未選択だと送信ボタンが押せない
      await ta.page.reload()
      await ta.page.getByTestId('team-affiliation-apply-button').click()
      await ta.page.getByTestId('apply-team-select').click()
      await ta.page.getByRole('option', { name: new RegExp(w.T4.name) }).filter({ visible: true }).first().click()
      await expect(ta.page.getByTestId('apply-group-select')).toBeVisible()
      await expect(ta.page.getByTestId('apply-submit')).toBeDisabled()
      await chooseInSelect(ta.page, ta.page.getByTestId('apply-group-select'), G.A)
      await expect(ta.page.getByTestId('apply-submit')).toBeEnabled()
      const applied = await clickAndWait(ta.page, () => ta.page.getByTestId('apply-submit').click(), 'POST', `/api/v1/teams/${w.T4.slug}/org-applications`)
      expect(applied.status(), await applied.text()).toBe(201)
      expect(applied.request().postDataJSON()).toMatchObject({ groupId: idA })
    } finally {
      await ta.context.close()
    }
    // AC-B10（契約）: group_id なしは 400 TEAM_067。AC-B12: 他組織のグループ ID は 400 TEAM_072
    const noGroup = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T5.slug}/org-applications`, { organizationSlug: w.X.slug })
    expect([noGroup.status, noGroup.body.error?.code], 'B10').toEqual([400, 'TEAM_067'])
    const foreign = await apiAs(w.TA, 'POST', `/api/v1/teams/${w.T5.slug}/org-applications`, { organizationSlug: w.X.slug, groupId: w.yGroupId })
    expect([foreign.status, foreign.body.error?.code], 'B12').toEqual([400, 'TEAM_072'])
    // 前提: T5・T6 も G.A 希望で申請（承認の画面操作が対象）
    for (const team of [w.T5, w.T6]) await must(w.TA, 'POST', `/api/v1/teams/${team.slug}/org-applications`, { organizationSlug: w.X.slug, groupId: idA })

    const approveRow = async (team: Scope, pick: string | null) => {
      await xa.page.goto(`/organizations/${w.X.slug}/member-teams?view=applications`)
      await enableAdminLens(xa.page)
      const row = xa.page.getByTestId('application-row').filter({ hasText: team.name })
      await expect(row).toBeVisible({ timeout: 30_000 })
      await row.getByRole('button', { name: '承認' }).click()
      await expect(xa.page.getByTestId('approve-group-select')).toContainText(G.A)
      if (pick) {
        await chooseInSelect(xa.page, xa.page.getByTestId('approve-group-select'), pick)
        await expect(xa.page.getByTestId('approve-group-change')).toContainText(`希望: ${G.A} → 承認時: ${pick}`)
      }
      const res = await clickAndWait(xa.page, () => xa.page.getByTestId('approve-submit').click(), 'POST', new RegExp(`/team-applications/[^/]+/approve$`))
      expect(res.status(), await res.text()).toBe(200)
      return res.request().postDataJSON()
    }
    try {
      // AC-C01: 希望のまま承認 → G.A に所属
      expect(await approveRow(w.T4, null)).toMatchObject({ overrideGroup: false })
      expect(membershipGroup(w.T4.slug)).toBe(idA)
      // AC-C02: 別グループを指定 → G.C2 に所属し、監査ログに希望と確定の両方
      expect(await approveRow(w.T5, G.C2)).toMatchObject({ overrideGroup: true, groupId: idC2 })
      expect(membershipGroup(w.T5.slug)).toBe(idC2)
      const audit = sql(`SELECT CONCAT(event_type, ' ', IFNULL(CAST(metadata AS CHAR),'')) FROM audit_logs WHERE organization_id=${w.X.numericId} ORDER BY id DESC LIMIT 10;`)
      expect(audit, 'AC-C02 監査ログ').toContain(idA)
      expect(audit, 'AC-C02 監査ログ').toContain(idC2)
      // AC-C03: REQUIRED でも「未分類」で承認できる
      const payload = await approveRow(w.T6, '未分類')
      expect(payload).toMatchObject({ overrideGroup: true })
      expect(payload.groupId ?? null).toBeNull()
      expect(membershipGroup(w.T6.slug)).toBe('NULL')
      // 一覧のグループ列にも反映
      await xa.page.getByTestId('view-tab-teams').click()
      await expect(teamRow(xa.page, w.T5).getByTestId('team-group-label')).toHaveText(G.C2, { timeout: 30_000 })
      await expect(teamRow(xa.page, w.T6).getByTestId('team-group-label')).toHaveText('未分類')

      // AC-D11: 一時グループを指定して T7 を招待 → 承諾前にグループ削除 → 承諾は 200 で未分類
      const temp = `一時 ${RUN}`
      await xa.page.getByTestId('view-tab-groups').click()
      expect(await createGroupViaUi(xa.page, temp)).toBe(201)
      const idTemp = groupIdOf(w.X.slug, temp)
      await xa.page.getByTestId('view-tab-invites').click()
      await xa.page.getByTestId('invite-open').click()
      await xa.page.getByTestId('invite-search-input').fill(w.T7.name)
      await xa.page.getByTestId('invite-search-submit').click()
      await xa.page.getByTestId(`invite-result-${w.T7.slug}`).click()
      await chooseInSelect(xa.page, xa.page.getByTestId('invite-group-select'), temp)
      const invited = await clickAndWait(xa.page, () => xa.page.getByTestId('invite-submit').click(), 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`)
      expect(invited.status(), await invited.text()).toBe(201)
      expect(invited.request().postDataJSON()).toMatchObject({ groupId: idTemp })
      await xa.page.getByTestId('view-tab-groups').click()
      await xa.page.getByTestId(`group-delete-${idTemp}`).click()
      const del = await clickAndWait(xa.page, () => xa.page.getByTestId('group-delete-confirm').click(), 'DELETE', `/api/v1/organizations/${w.X.slug}/team-groups/${idTemp}`)
      expect(del.status()).toBe(204)
    } finally {
      await xa.context.close()
    }
    const t7 = await openAs(browser, w.TA, `/teams/${w.T7.slug}/affiliations?view=invites`)
    try {
      const row = t7.page.getByTestId('invite-row').filter({ hasText: w.X.name })
      await expect(row).toBeVisible({ timeout: 30_000 })
      const accepted = await clickAndWait(t7.page, () => row.getByRole('button', { name: '承諾' }).click(), 'POST', new RegExp(`/org-invites/\\d+/accept$`))
      expect(accepted.status(), 'AC-D11').toBe(200)
    } finally {
      await t7.context.close()
    }
    await expect.poll(() => membershipGroup(w.T7.slug), { timeout: 30_000 }).toBe('NULL')
    // AC-D10 後半: グループ機能 on でも他組織のグループ付き招待は 400 TEAM_072
    const T8 = await createTeam(w.TA, `実機G八 ${RUN}`)
    const d10 = await apiAs(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-invites`, { teamSlug: T8.slug, groupId: w.yGroupId })
    expect([d10.status, d10.body.error?.code], 'D10').toEqual([400, 'TEAM_072'])
    await archiveScope('teams', w.TA, T8.slug)
  })

  test('GRP-09 [AC-F03] 99 件ある状態で 100 件目を画面から作れ、上限到達で作成ボタンが無効・API の 101 件目は 422 ORG_066', async ({ browser }) => {
    test.setTimeout(600_000)
    const count = Number(sql(`SELECT COUNT(*) FROM org_team_groups g JOIN organizations o ON o.id=g.organization_id WHERE o.slug='${w.X.slug}' AND g.deleted_at IS NULL;`))
    for (let i = count; i < 99; i++) await createGroup(w.XA, w.X.slug, `上限確認 ${i} ${RUN}`)
    const s = await openAs(browser, w.XA, `/organizations/${w.X.slug}/member-teams?view=groups`)
    const page = s.page
    try {
      await dismissPermissionSetup(page)
      await enableAdminLens(page)
      await expect(page.getByTestId('group-row')).toHaveCount(99, { timeout: 30_000 })
      expect(await createGroupViaUi(page, `百件目 ${RUN}`)).toBe(201)
      await expect(page.getByTestId('group-row')).toHaveCount(100)
      await expect(page.getByTestId('group-create-open')).toBeDisabled()
      await expect(page.getByTestId('group-limit-reached')).toContainText('チームグループは100件までです')
    } finally {
      await s.context.close()
    }
    const over = await apiAs(w.XA, 'POST', `/api/v1/organizations/${w.X.slug}/team-groups`, { name: `百一件目 ${RUN}` })
    expect(over.status).toBe(422)
    expect(over.body.error.code).toBe('ORG_066')
  })
})

test('GRP-99 後始末', async () => {
  const results = [
    await archiveScope('teams', w.TA, w.T1.slug),
    await archiveScope('teams', w.TA, w.T2.slug),
    await archiveScope('teams', w.TA, w.T3.slug),
    await archiveScope('teams', w.TA, w.T4.slug),
    await archiveScope('teams', w.TA, w.T5.slug),
    await archiveScope('teams', w.TA, w.T6.slug),
    await archiveScope('teams', w.TA, w.T7.slug),
    await archiveScope('organizations', w.XA, w.X.slug),
    await archiveScope('organizations', w.YA, w.Y.slug),
  ]
  console.log(`[F0121-G] cleanup statuses ${results.join(',')}`)
})
