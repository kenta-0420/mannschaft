import { test, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
/**
 * 注記: 2026-10-02 時点で未実走。FE dev サーバがビルド後に HTTP 応答しなくなる環境不具合で画面を開けなかった。
 * API の AC は curl で確認済み。
 */

/**
 * 実機E2E: CMP-260929-0654（PR #3555）大会エントリー系3表・テンプレート staff・user_interest_tags の
 * 主キーを CHAR(36) → BINARY(16) に移行した後も、実 BE（ddl-auto:none・Flyway V234）で
 * エントリー表メンバー・エントリーテンプレートが動くこと。
 *
 * 以前は `Incorrect string value ... for column 'id'` で PUT entry-members / POST entry-templates が 500 だった。
 *
 * 前提（開発DB・実 BE/FE。CI 対象外・殿が手動実走）:
 *   - USER  = e2e-user(23): org 9 ADMIN（主催組織の ADMIN）／ team 1・2 は MEMBER ／ org 1・4 は MEMBER ／ team 4 は MEMBER
 *   - ADMIN = e2e-admin(24): SYSTEM_ADMIN（組織・チームの実 ADMIN ではない。大会作成と参加チーム追加に使う）
 *   - 資格情報は .env.test（TEST_USER_* / TEST_ADMIN_*）から読む。値は spec に書かない。
 *   - 前提データ（大会・ディビジョン・参加チーム）は本 spec が API で作る（これは「前提データ作成」）。
 *
 * 画面か API か:
 *   - 画面: org 大会詳細の「エントリー管理」モーダル（メンバー一覧・保存・テンプレ適用）と、roster.vue のテンプレ読込。
 *   - API : テンプレートの作成・更新・削除・文字数上限・ロール横断・他テナント 404。
 *          （テンプレート作成・メンバー番号入力の UI が無いことは既知 = CMP-260930-0228）
 *
 * 実行: BASE_URL=http://localhost:3003 API_BASE_URL=http://localhost:8080 npx playwright test cmp-260929-0654 --project=chromium-real --no-deps --reporter=list
 *
 * ブラウザは既定の localhost→127.0.0.1 マップ（playwright-real.config.ts）を外す。検証 FE は ::1 のみで待ち受けるため。
 */
test.use({ launchOptions: { args: [] }, storageState: { cookies: [], origins: [] } })
test.setTimeout(240_000)

const API = process.env.API_BASE_URL ?? 'http://localhost:8080'
const SUFFIX = String(Date.now())

// 主催組織 A（USER が ADMIN）。参加チーム 1・2（USER は MEMBER）
const ORG_A = 9
const ORG_A_SLUG = 'org-000009'
const TEAM_A1 = 1
const TEAM_A2 = 2
// 権限なし側: org 4 / team 4（USER は両方 MEMBER）。entry-members 側は org 1（USER は MEMBER）の大会に team 4 を参加させて使う
const ORG_B = 4
const TEAM_B = 4
const ORG_C = 1
const ORG_C_SLUG = 'org-000001'

const USER_ID = 23
const ADMIN_USER_ID = 24

const userCred = { email: process.env.TEST_USER_EMAIL ?? '', password: process.env.TEST_USER_PASSWORD ?? '' }
const adminCred = { email: process.env.TEST_ADMIN_EMAIL ?? '', password: process.env.TEST_ADMIN_PASSWORD ?? '' }

const POS30 = 'P'.repeat(30)
const POS31 = 'P'.repeat(31)
const NOTES200 = 'N'.repeat(200)
const NOTES201 = 'N'.repeat(201)


async function newPage(browser: Browser, cred: { email: string; password: string }): Promise<Page> {
  const ctx = await browser.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost:3003', locale: 'ja-JP' })
  const page = await ctx.newPage()
  await loginViaApi(page, cred, { apiBaseUrl: API })
  return page
}

async function api(req: APIRequestContext, method: 'get' | 'post' | 'put' | 'patch' | 'delete', path: string, data?: unknown) {
  const res = await req[method](`${API}/api/v1${path}`, data === undefined ? undefined : { data })
  const text = await res.text()
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- 実 BE の多様な応答本文を読む試験用ヘルパ
  let body: { data?: any; error?: { code?: string; fieldErrors?: { field: string }[] } } = {}
  try { body = JSON.parse(text) } catch { /* 本文なし */ }
  return { status: res.status(), body, text }
}

/** 5xx と console error を集める。 */
function watchErrors(page: Page) {
  const server: string[] = []
  const consoleErrors: string[] = []
  page.on('response', (r) => { if (r.url().includes('/api/v1/') && r.status() >= 500) server.push(`${r.status()} ${r.url()}`) })
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`))
  return { server, consoleErrors }
}

const base = (orgId: number, tId: number, divId: number, pId: number) =>
  `/organizations/${orgId}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members`

// 前提データ
let tIdA = 0
let divA = 0
let pA1 = 0
let pA2 = 0
let tIdC = 0
let divC = 0
let pC4 = 0
const createdTemplates: { org: number; team: number; id: string }[] = []

async function ensureParticipant(req: APIRequestContext, orgId: number, tId: number, divId: number, teamId: number): Promise<number> {
  const list = await api(req, 'get', `/organizations/${orgId}/tournaments/${tId}/divisions/${divId}/participants`)
  const found = (list.body.data as { id: number; teamId: number }[] | undefined)?.find((p) => p.teamId === teamId)
  if (found) return found.id
  const add = await api(req, 'post', `/organizations/${orgId}/tournaments/${tId}/divisions/${divId}/participants`, { teamId, seed: 1 })
  expect(add.status, add.text).toBe(201)
  return Number(add.body.data?.id)
}

test.describe.serial('CMP-260929-0654 主キー BINARY(16) 移行 実機E2E', () => {
  test.beforeAll(async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    const user = await newPage(browser, userCred)

    // 前提データ作成: org 9 の大会（作成は SYSTEM_ADMIN、ディビジョン・参加チーム追加は主催組織 ADMIN の USER）
    const t = await api(admin.request, 'post', `/organizations/${ORG_A}/tournaments`, {
      name: `PKB16-${SUFFIX}`, format: 'LEAGUE', season: '2026', startDate: '2026-10-10', endDate: '2026-12-30',
      winPoints: 3, drawPoints: 1, lossPoints: 0, hasDraw: true, hasSets: false, visibility: 'MEMBERS_AND_ABOVE',
    })
    expect(t.status, t.text).toBe(201)
    tIdA = Number(t.body.data?.id)
    const d = await api(user.request, 'post', `/organizations/${ORG_A}/tournaments/${tIdA}/divisions`, {
      name: 'D1', level: 1, promotionSlots: 0, relegationSlots: 0, maxParticipants: 12, sortOrder: 1,
    })
    expect(d.status, d.text).toBe(201)
    divA = Number(d.body.data?.id)
    pA1 = await ensureParticipant(user.request, ORG_A, tIdA, divA, TEAM_A1)
    pA2 = await ensureParticipant(user.request, ORG_A, tIdA, divA, TEAM_A2)

    // 権限なし側: org 1 の既存 DRAFT/OPEN 大会のディビジョンに team 4 を参加させる（参加追加は SYSTEM_ADMIN）
    const list = await api(admin.request, 'get', `/organizations/${ORG_C}/tournaments?size=50`)
    expect(list.status, list.text).toBe(200)
    const cands = (list.body.data as { id: number; structure?: { status?: string } }[]).filter((x) => ['DRAFT', 'OPEN'].includes(x.structure?.status ?? ''))
    for (const c of cands) {
      const divs = await api(admin.request, 'get', `/organizations/${ORG_C}/tournaments/${c.id}/divisions`)
      const first = (divs.body.data as { id: number }[] | undefined)?.[0]
      if (first) { tIdC = c.id; divC = first.id; break }
    }
    expect(tIdC, 'org 1 に DRAFT/OPEN でディビジョンを持つ大会が必要').toBeGreaterThan(0)
    pC4 = await ensureParticipant(admin.request, ORG_C, tIdC, divC, TEAM_B)

    await admin.context().close()
    await user.context().close()
  })

  // ---------------------------------------------------------------- AC1 + AC3（メンバー一括登録）
  test('AC1/AC3 [API] entry-members の一括登録が 200・GET で position/notes まで一致・文字数上限（USER=主催組織 ADMIN / SYSTEM_ADMIN）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const url = base(ORG_A, tIdA, divA, pA1)

    const members = [
      { userId: USER_ID, jerseyNumber: 10, position: POS30, notes: NOTES200, sortOrder: 1 },
      { userId: ADMIN_USER_ID, jerseyNumber: null, position: null, notes: null, sortOrder: 2 },
    ]
    const put = await api(user.request, 'put', url, { members })
    expect(put.status, put.text).toBe(200) // 以前は Incorrect string value ... for column 'id' の 500
    const got = await api(user.request, 'get', url)
    expect(got.status).toBe(200)
    const em = got.body.data.entryMembers as { id: string; userId: number; jerseyNumber: number | null; position: string | null; notes: string | null; sortOrder: number }[]
    expect(em).toHaveLength(2)
    expect(em[0]).toMatchObject({ userId: USER_ID, jerseyNumber: 10, position: POS30, notes: NOTES200, sortOrder: 1 })
    expect(em[1]).toMatchObject({ userId: ADMIN_USER_ID, jerseyNumber: null, position: null, notes: null, sortOrder: 2 })
    // 主キーは UUID 文字列で往復する
    for (const m of em) expect(m.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    expect(got.body.data.entryCount).toBe(2)

    // 全置換の再送（DELETE→INSERT の flush 順）も 200
    const again = await api(user.request, 'put', url, { members })
    expect(again.status, again.text).toBe(200)

    // 31字・201字は 400（fieldErrors つき）
    const badPos = await api(user.request, 'put', url, { members: [{ userId: USER_ID, position: POS31 }] })
    expect(badPos.status).toBe(400)
    expect(badPos.body.error?.fieldErrors?.map((f) => f.field)).toContain('members[0].position')
    const badNotes = await api(user.request, 'put', url, { members: [{ userId: USER_ID, notes: NOTES201 }] })
    expect(badNotes.status).toBe(400)
    expect(badNotes.body.error?.fieldErrors?.map((f) => f.field)).toContain('members[0].notes')
    // 400 のとき既存の内容は壊れない
    const after = await api(user.request, 'get', url)
    expect(after.body.data.entryCount).toBe(2)
    await user.context().close()

    // SYSTEM_ADMIN（e2e-admin）も 200（もう一方の participant で）
    const admin = await newPage(browser, adminCred)
    const putAdmin = await api(admin.request, 'put', base(ORG_A, tIdA, divA, pA2), { members: [{ userId: USER_ID, jerseyNumber: 3, position: 'GK', notes: 'admin', sortOrder: 1 }] })
    expect(putAdmin.status, putAdmin.text).toBe(200)
    const gotAdmin = await api(admin.request, 'get', base(ORG_A, tIdA, divA, pA2))
    expect(gotAdmin.body.data.entryMembers[0]).toMatchObject({ userId: USER_ID, jerseyNumber: 3, position: 'GK', notes: 'admin' })
    await admin.context().close()
  })

  // ---------------------------------------------------------------- AC2 + AC3（テンプレート）
  test('AC2/AC3 [API] エントリーテンプレートの作成 201・取得・更新・論理削除と position の上限（USER=主催組織 ADMIN）', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const tplBase = `/organizations/${ORG_A}/teams/${TEAM_A1}/entry-templates`

    const create = await api(user.request, 'post', tplBase, {
      name: `PKB16-tpl-${SUFFIX}`, description: 'd', sortOrder: 1,
      members: [{ userId: USER_ID, jerseyNumber: 7, position: POS30, sortOrder: 1 }],
    })
    expect(create.status, create.text).toBe(201) // 以前は 500
    const tplId = String(create.body.data.id)
    createdTemplates.push({ org: ORG_A, team: TEAM_A1, id: tplId })
    expect(tplId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    expect(create.body.data.members[0]).toMatchObject({ userId: USER_ID, jerseyNumber: 7, position: POS30 })

    // 一覧・詳細
    const list = await api(user.request, 'get', tplBase)
    expect(list.status).toBe(200)
    expect((list.body.data as { id: string; name: string }[]).map((x) => x.id)).toContain(tplId)
    const detail = await api(user.request, 'get', `${tplBase}/${tplId}`)
    expect(detail.status).toBe(200)
    expect(detail.body.data.members[0]).toMatchObject({ userId: USER_ID, position: POS30 })

    // 31字は 400（作成・更新とも）
    const badCreate = await api(user.request, 'post', tplBase, { name: 'bad', members: [{ userId: USER_ID, position: POS31 }] })
    expect(badCreate.status).toBe(400)
    expect(badCreate.body.error?.fieldErrors?.map((f) => f.field)).toContain('members[0].position')
    const badUpdate = await api(user.request, 'put', `${tplBase}/${tplId}`, { name: 'bad', members: [{ userId: USER_ID, position: POS31 }] })
    expect(badUpdate.status).toBe(400)
    expect(badUpdate.body.error?.fieldErrors?.map((f) => f.field)).toContain('members[0].position')

    // 更新（members 全置換）
    const upd = await api(user.request, 'put', `${tplBase}/${tplId}`, {
      name: `PKB16-tpl-upd-${SUFFIX}`, description: null, members: [{ userId: ADMIN_USER_ID, position: 'GK', sortOrder: 1 }, { userId: USER_ID, position: POS30, sortOrder: 2 }],
    })
    expect(upd.status, upd.text).toBe(200)
    expect(upd.body.data.name).toBe(`PKB16-tpl-upd-${SUFFIX}`)
    expect(upd.body.data.members).toHaveLength(2)

    // テンプレ適用（エントリー表へ反映）
    const apply = await api(user.request, 'post', `${base(ORG_A, tIdA, divA, pA1)}/apply-template`, { templateId: tplId, overwriteExisting: true })
    expect(apply.status, apply.text).toBe(200)

    // 論理削除 → 取得 404・一覧から消える
    const del = await api(user.request, 'delete', `${tplBase}/${tplId}`)
    expect(del.status).toBe(204)
    expect((await api(user.request, 'get', `${tplBase}/${tplId}`)).status).toBe(404)
    const list2 = await api(user.request, 'get', tplBase)
    expect((list2.body.data as { id: string }[]).map((x) => x.id)).not.toContain(tplId)
    createdTemplates.pop()
    await user.context().close()
  })

  // ---------------------------------------------------------------- AC1/AC2/AC5（画面）
  test('AC1/AC2/AC5 [画面] 大会詳細の「エントリー管理」モーダルで一覧・保存（position/notes）・テンプレ適用ができ、500 もエラートーストも出ない', async ({ browser }) => {
    // 前提データ作成（API）: 画面から選べるテンプレートを 1 件
    const user = await newPage(browser, userCred)
    const errs = watchErrors(user)
    const tpl = await api(user.request, 'post', `/organizations/${ORG_A}/teams/${TEAM_A1}/entry-templates`, {
      name: `PKB16-ui-${SUFFIX}`, members: [{ userId: USER_ID, jerseyNumber: 5, position: 'DF', sortOrder: 1 }],
    })
    expect(tpl.status, tpl.text).toBe(201)
    createdTemplates.push({ org: ORG_A, team: TEAM_A1, id: String(tpl.body.data.id) })

    await user.goto(`/organizations/${ORG_A_SLUG}/tournaments/${tIdA}`)
    await waitForHydration(user)
    const manage = user.getByRole('button', { name: 'エントリー管理' }).first()
    await expect(manage).toBeVisible({ timeout: 60_000 })
    await manage.click()
    const dialog = user.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 20_000 })

    // 既存エントリー（API で入れた participant pA1 の内容）が画面に出る。先に 1 行目の participant が pA1 とは限らないので、
    // どちらの participant でも「保存」が通ることを確かめる。
    // テンプレート一覧に API 作成分が出る（BE の BINARY(16) 読み出し → UUID 文字列）
    await expect(dialog.getByText('エントリーテンプレート')).toBeVisible()
    await dialog.locator('.p-select').click()
    await expect(user.getByRole('option', { name: `PKB16-ui-${SUFFIX}` })).toBeVisible({ timeout: 20_000 })
    await user.getByRole('option', { name: `PKB16-ui-${SUFFIX}` }).click()
    await dialog.getByRole('button', { name: '一括適用' }).click()
    await expect(user.getByText('件追加しました').first()).toBeVisible({ timeout: 20_000 })

    // 手動編集: 先頭の候補を選択し position・notes を入れて保存
    const firstCheck = dialog.locator('[id^="member-"]').first()
    const boxes = dialog.locator('.p-checkbox')
    await expect(boxes.first()).toBeVisible({ timeout: 20_000 })
    expect(await firstCheck.count()).toBeGreaterThan(0)
    const row = dialog.locator('div.rounded.border').filter({ has: dialog.locator('[id^="member-"]') }).first()
    const rowCheckbox = row.locator('.p-checkbox').first()
    if (!(await row.locator('input[type="checkbox"]').first().isChecked())) await rowCheckbox.click()
    await row.getByPlaceholder('ポジション').fill('MF')
    await row.getByPlaceholder('備考').fill(`ui-notes-${SUFFIX}`)
    await dialog.getByRole('button', { name: 'エントリーを保存' }).last().click()
    await expect(user.getByText('エントリーを保存しました')).toBeVisible({ timeout: 20_000 })

    // 保存内容が API でも同じ（画面で保存した participant を探す）
    const a1 = await api(user.request, 'get', base(ORG_A, tIdA, divA, pA1))
    const a2 = await api(user.request, 'get', base(ORG_A, tIdA, divA, pA2))
    const all = [...(a1.body.data.entryMembers as { notes: string | null; position: string | null }[]), ...(a2.body.data.entryMembers as { notes: string | null; position: string | null }[])]
    expect(all.some((m) => m.notes === `ui-notes-${SUFFIX}` && m.position === 'MF'), JSON.stringify(all)).toBe(true)

    // 一覧に反映され、エラートーストが無い
    await expect(dialog.getByText(`ui-notes-${SUFFIX}`)).toBeVisible({ timeout: 20_000 })
    await expect(user.locator('.p-toast-message-error')).toHaveCount(0)
    expect(errs.server, errs.server.join('\n')).toHaveLength(0)
    expect(errs.consoleErrors, errs.consoleErrors.join('\n')).toHaveLength(0)
    await user.context().close()
  })

  test('AC2/AC5 [画面] チームの roster.vue がエントリーテンプレートを読みに行き、500 を出さない', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const errs = watchErrors(user)
    const tplReqs: { url: string; status: number }[] = []
    user.on('response', (r) => { if (r.url().includes('/entry-templates')) tplReqs.push({ url: r.url(), status: r.status() }) })
    await user.goto(`/teams/fc-u-18/tournaments/${tIdA}/roster?matchId=1`)
    await waitForHydration(user)
    await user.waitForTimeout(5000)
    // テンプレ取得リクエストが飛ぶこと（飛ばなければ画面がテンプレを読んでいない）
    expect(tplReqs.length, '画面が entry-templates を読みに行く').toBeGreaterThan(0)
    // 500 は出ない（4xx は下の既知欠陥注釈で扱う）
    expect(errs.server, errs.server.join('\n')).toHaveLength(0)
    const bad = tplReqs.filter((r) => r.status !== 200)
    if (bad.length > 0) {
      test.info().annotations.push({ type: 'known-defect', description: `roster.vue が組織 ID '0' / チーム slug でテンプレを取得し ${bad.map((b) => b.status).join(',')}（テンプレ適用ボタンが出ない）: ${bad[0]!.url}` })
    }
    await user.context().close()
  })

  // ---------------------------------------------------------------- AC4（ロール横断）
  test('AC4 [API+画面] 権限なし（org/team とも MEMBER の USER）は作成・更新・削除・登録が 403、閲覧は 200。画面に管理ボタンが出ない', async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    const user = await newPage(browser, userCred)
    const tplBaseB = `/organizations/${ORG_B}/teams/${TEAM_B}/entry-templates`

    // 権限あり（SYSTEM_ADMIN）が org 4 / team 4 にテンプレートを作る → 201
    const create = await api(admin.request, 'post', tplBaseB, { name: `PKB16-b-${SUFFIX}`, members: [{ userId: USER_ID, position: 'FW', sortOrder: 1 }] })
    expect(create.status, create.text).toBe(201)
    const tid = String(create.body.data.id)
    createdTemplates.push({ org: ORG_B, team: TEAM_B, id: tid })

    // 権限なし（MEMBER）: 作成・更新・削除は 403、閲覧（一覧・詳細）は 200
    const memberCreate = await api(user.request, 'post', tplBaseB, { name: 'x', members: [{ userId: USER_ID }] })
    expect(memberCreate.status).toBe(403)
    expect((await api(user.request, 'put', `${tplBaseB}/${tid}`, { name: 'x', members: [{ userId: USER_ID }] })).status).toBe(403)
    expect((await api(user.request, 'delete', `${tplBaseB}/${tid}`)).status).toBe(403)
    expect((await api(user.request, 'get', tplBaseB)).status).toBe(200)
    expect((await api(user.request, 'get', `${tplBaseB}/${tid}`)).status).toBe(200)
    // 拒否されても内容は無傷
    const still = await api(admin.request, 'get', `${tplBaseB}/${tid}`)
    expect(still.body.data.name).toBe(`PKB16-b-${SUFFIX}`)

    // エントリー表: 権限なし（org 1 MEMBER / team 4 MEMBER）は登録・全削除・一括ロード・テンプレ適用が 403、閲覧は 200
    const eb = base(ORG_C, tIdC, divC, pC4)
    const memberPut = await api(user.request, 'put', eb, { members: [{ userId: USER_ID, position: 'FW' }] })
    expect(memberPut.status).toBe(403)
    expect((await api(user.request, 'post', `${eb}/load-from-team`, { overwriteExisting: false })).status).toBe(403)
    expect((await api(user.request, 'post', `${eb}/apply-template`, { templateId: tid, overwriteExisting: false })).status).toBe(403)
    expect((await api(user.request, 'get', eb)).status).toBe(200)
    // 権限あり（SYSTEM_ADMIN）は 200
    const adminPut = await api(admin.request, 'put', eb, { members: [{ userId: USER_ID, position: 'FW', notes: 'b', sortOrder: 1 }] })
    expect(adminPut.status, adminPut.text).toBe(200)
    const entryId = String((adminPut.body.data.entryMembers as { id: string }[])[0]!.id)
    // 権限なしの個別削除は 403 で、エントリーは残る
    expect((await api(user.request, 'delete', `${eb}/${entryId}`)).status).toBe(403)
    expect(((await api(admin.request, 'get', eb)).body.data.entryMembers as unknown[]).length).toBe(1)

    // 画面: MEMBER には「エントリー管理」ボタンが出ない（500 なし）
    const errs = watchErrors(user)
    await user.goto(`/organizations/${ORG_C_SLUG}/tournaments/${tIdC}`)
    await waitForHydration(user)
    await user.waitForTimeout(5000)
    await expect(user.getByRole('button', { name: 'エントリー管理' })).toHaveCount(0)
    expect(errs.server, errs.server.join('\n')).toHaveLength(0)

    // 後始末（API）
    await api(admin.request, 'put', eb, { members: [] })
    await admin.context().close()
    await user.context().close()
  })

  test('AC4 [API] 他チーム・他組織のテンプレート ID／エントリーメンバー ID を指定すると 404', async ({ browser }) => {
    const user = await newPage(browser, userCred)
    const admin = await newPage(browser, adminCred)
    const tplA1 = `/organizations/${ORG_A}/teams/${TEAM_A1}/entry-templates`
    const tplA2 = `/organizations/${ORG_A}/teams/${TEAM_A2}/entry-templates`

    const t1 = await api(user.request, 'post', tplA1, { name: `PKB16-x1-${SUFFIX}`, members: [{ userId: USER_ID, position: 'A' }] })
    const t2 = await api(user.request, 'post', tplA2, { name: `PKB16-x2-${SUFFIX}`, members: [{ userId: USER_ID, position: 'B' }] })
    expect(t1.status, t1.text).toBe(201)
    expect(t2.status, t2.text).toBe(201)
    const id1 = String(t1.body.data.id)
    const id2 = String(t2.body.data.id)
    createdTemplates.push({ org: ORG_A, team: TEAM_A1, id: id1 }, { org: ORG_A, team: TEAM_A2, id: id2 })

    // 他チーム（同一組織）のテンプレート ID: 取得・更新・削除・適用すべて 404、相手のテンプレートは無傷
    expect((await api(user.request, 'get', `${tplA1}/${id2}`)).status).toBe(404)
    expect((await api(user.request, 'put', `${tplA1}/${id2}`, { name: 'hijack', members: [{ userId: USER_ID }] })).status).toBe(404)
    expect((await api(user.request, 'delete', `${tplA1}/${id2}`)).status).toBe(404)
    const apply = await api(user.request, 'post', `${base(ORG_A, tIdA, divA, pA1)}/apply-template`, { templateId: id2, overwriteExisting: false })
    expect(apply.status, apply.text).toBe(404)
    const intact = await api(user.request, 'get', `${tplA2}/${id2}`)
    expect(intact.status).toBe(200)
    expect(intact.body.data.name).toBe(`PKB16-x2-${SUFFIX}`)

    // 他組織（org 4 / team 4）のテンプレート ID を org 9 / team 1 のパスで指定: 404
    const other = await api(admin.request, 'post', `/organizations/${ORG_B}/teams/${TEAM_B}/entry-templates`, { name: `PKB16-o-${SUFFIX}`, members: [{ userId: USER_ID, position: 'C' }] })
    expect(other.status, other.text).toBe(201)
    const idO = String(other.body.data.id)
    createdTemplates.push({ org: ORG_B, team: TEAM_B, id: idO })
    expect((await api(user.request, 'get', `${tplA1}/${idO}`)).status).toBe(404)
    expect((await api(admin.request, 'get', `${tplA1}/${idO}`)).status).toBe(404)
    // 組織に属さないチームのパス（team 4 は org 9 に属さない）: 404
    expect((await api(user.request, 'get', `/organizations/${ORG_A}/teams/${TEAM_B}/entry-templates`)).status).toBe(404)

    // 他チームのエントリーメンバー ID（participant を取り違えた削除）: 404 で、元のエントリーは残る
    const seed = await api(user.request, 'put', base(ORG_A, tIdA, divA, pA1), { members: [{ userId: USER_ID, position: 'S', sortOrder: 1 }] })
    expect(seed.status, seed.text).toBe(200)
    const emId = String((seed.body.data.entryMembers as { id: string }[])[0]!.id)
    expect((await api(user.request, 'delete', `${base(ORG_A, tIdA, divA, pA2)}/${emId}`)).status).toBe(404)
    // 他組織の大会・participant 経由（org 1 の participant を org 9 のパスで指定）: 404
    expect((await api(user.request, 'delete', `${base(ORG_A, tIdC, divC, pC4)}/${emId}`)).status).toBe(404)
    expect((await api(user.request, 'get', base(ORG_A, tIdC, divC, pC4))).status).toBe(404)
    expect((await api(user.request, 'put', base(ORG_A, tIdC, divC, pC4), { members: [{ userId: USER_ID }] })).status).toBe(404)
    expect(((await api(user.request, 'get', base(ORG_A, tIdA, divA, pA1))).body.data.entryMembers as unknown[]).length).toBe(1)

    // 正しい経路での個別削除は 204
    expect((await api(user.request, 'delete', `${base(ORG_A, tIdA, divA, pA1)}/${emId}`)).status).toBe(204)
    await user.context().close()
    await admin.context().close()
  })

  test.afterAll(async ({ browser }) => {
    const admin = await newPage(browser, adminCred)
    const user = await newPage(browser, userCred)
    for (const t of createdTemplates) {
      await api(admin.request, 'delete', `/organizations/${t.org}/teams/${t.team}/entry-templates/${t.id}`)
    }
    await api(user.request, 'delete', `/organizations/${ORG_A}/tournaments/${tIdA}`)
    await admin.context().close()
    await user.context().close()
  })
})
