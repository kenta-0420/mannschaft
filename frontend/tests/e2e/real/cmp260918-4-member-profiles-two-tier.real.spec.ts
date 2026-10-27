import {
  expect,
  request as pwRequest,
  test,
  type APIRequestContext,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * CMP-261002-1343: 既存ユーザーの権限を変えず、自所有の組織で全5件を検証する。
 * 前提・本人確認・永続化確認・後始末は正規API、③のCRUDは実UIを通す。
 * 初期設定は表示と画面証跡を確認後、ダイアログ内の「あとで決める」で閉じる。
 */
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const uniqueSuffix = Date.now().toString(36)
const PAGE_SLUG = `cmp1343-ui-${uniqueSuffix}`
const PAGE_TITLE = `検証用ページ${uniqueSuffix}`
const MEMBER_NAME = `検証太郎${uniqueSuffix}`
const MEMBER_NAME_EDITED = `${MEMBER_NAME}編集済`
const accounts = {
  admin: { email: 'e2e-user@test.mannschaft.local', id: 3 },
  member: { email: 'e2e-dummy-6@test.mannschaft.local', id: 12 },
  outsider: { email: 'e2e-supporter@test.mannschaft.local', id: 5 },
}
type ActorName = keyof typeof accounts
type OwnPage = {
  id: number
  slug: string
  organizationId: number
  createdBy: number
  visibility: string
  status: string
}
let actors: Partial<Record<ActorName, APIRequestContext>> = {}
let organization: { numericId: number; slug: string } | undefined
let inviteId: number | undefined
const ownedPages = new Map<number, OwnPage>()
const ownedSlugs = new Map<string, number>()
const ownedProfiles = new Map<number, number>()
const deletedProfileIds = new Set<number>()
const contexts = new Set<BrowserContext>()

// 同一ファイル内は順番に実行するが、先行testの失敗で後続をskipしない。
test.describe.configure({ mode: 'default' })
// 認証・招待のpayloadをtraceへ保存しない。失敗の画面は通常のscreenshotで確認する。
test.use({ trace: 'off', video: 'off', storageState: { cookies: [], origins: [] } })

function password(): string {
  const value = process.env.TEST_USER_PASSWORD
  if (!value) throw new Error('TEST_USER_PASSWORD が未設定')
  return value
}

function adminApi(): APIRequestContext {
  if (!actors.admin) throw new Error('ADMIN fixtureが未作成')
  return actors.admin
}

function ownOrganization(): { numericId: number; slug: string } {
  if (!organization) throw new Error('専用組織fixtureが未作成')
  return organization
}

async function assertIdentity(api: APIRequestContext, name: ActorName): Promise<void> {
  const me = await api.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status(), `${name}の本人確認`).toBe(200)
  const identity = (await me.json()).data as {
    id: number
    email: string
    systemRole: string | null
  }
  expect(identity.id).toBe(accounts[name].id)
  expect(identity.email).toBe(accounts[name].email)
  expect(identity.systemRole).not.toBe('SYSTEM_ADMIN')
}

async function loginActor(name: ActorName): Promise<void> {
  const loginApi = await pwRequest.newContext({ baseURL: API_BASE_URL })
  try {
    const response = await loginApi.post('/api/v1/auth/login', {
      data: { email: accounts[name].email, password: password() },
    })
    expect(response.status(), `${name}のfixtureログイン`).toBe(200)
    const token = ((await response.json()).data as { accessToken: string }).accessToken
    expect(typeof token, 'ログイン応答のtoken型').toBe('string')
    actors[name] = await pwRequest.newContext({
      baseURL: API_BASE_URL,
      extraHTTPHeaders: { Authorization: `Bearer ${token}` },
    })
    await assertIdentity(actors[name]!, name)
  } finally {
    await loginApi.dispose()
  }
}

function rememberPage(data: OwnPage, slug: string): void {
  // 返却IDは表示assertより前に回収し、所有スコープを確認してからcleanup対象にする。
  expect(data.organizationId).toBe(ownOrganization().numericId)
  expect(ownedSlugs.has(slug), '作成を試みたexact slugだけを回収する').toBe(true)
  expect(data.createdBy).toBe(ownedSlugs.get(slug))
  expect(data.slug).toBe(slug)
  expect(Number.isSafeInteger(data.id)).toBe(true)
  ownedPages.set(data.id, data)
}

async function pageList(): Promise<OwnPage[]> {
  const response = await adminApi().get('/api/v1/team/pages', {
    params: { organizationId: ownOrganization().numericId, size: 100 },
  })
  expect(response.status(), '所有組織のページ一覧').toBe(200)
  return (await response.json()).data as OwnPage[]
}

/** 失敗時もexact own scope/slugからだけIDを回収する。未知データは削除しない。 */
async function cleanupFixture(): Promise<string[]> {
  const failures: string[] = []
  async function clean(label: string, action: () => Promise<void>): Promise<void> {
    try {
      await action()
    } catch {
      failures.push(label)
    }
  }
  if (organization && actors.admin) {
    await clean('所有ページIDの再照合', async () => {
      const pages = await pageList()
      for (const slug of ownedSlugs.keys()) {
        const matches = pages.filter((item) => item.slug === slug)
        expect(matches.length, '所有slugの一意性').toBeLessThanOrEqual(1)
        if (matches[0]) rememberPage(matches[0], slug)
      }
    })
    for (const ownPage of ownedPages.values()) {
      await clean(`所有プロフィールID回収:${ownPage.id}`, async () => {
        const detail = await adminApi().get(`/api/v1/team/pages/${ownPage.id}`)
        if (detail.status() === 404) return // 既知profile IDの回収はページ有無と独立に後続で行う。
        expect(detail.status()).toBe(200)
        rememberPage((await detail.json()).data as OwnPage, ownPage.slug)
        const members = await adminApi().get('/api/v1/team/members', {
          params: { teamPageId: ownPage.id, size: 100 },
        })
        expect(members.status()).toBe(200)
        for (const member of (await members.json()).data as { id: number; teamPageId: number }[]) {
          expect(member.teamPageId).toBe(ownPage.id)
          expect(Number.isSafeInteger(member.id)).toBe(true)
          ownedProfiles.set(member.id, member.teamPageId)
        }
      })
    }
    for (const [profileId, pageId] of ownedProfiles) {
      await clean(`所有プロフィール:${profileId}/親:${pageId}`, async () => {
        expect(ownedPages.has(pageId), '所有確認済みページのプロフィールだけを回収する').toBe(true)
        const detail = await adminApi().get(`/api/v1/team/members/${profileId}`)
        if (detail.status() === 404) {
          // 親ページ削除による404もあるため、成功DELETEを観測していないIDを回収済みにしない。
          expect(deletedProfileIds.has(profileId), 'プロフィールの削除成功を確認できない').toBe(
            true,
          )
          return
        }
        expect(detail.status()).toBe(200)
        expect((await detail.json()).data.teamPageId).toBe(pageId)
        const deletion = await adminApi().delete(`/api/v1/team/members/${profileId}`)
        expect(deletion.status()).toBe(204)
        deletedProfileIds.add(profileId)
      })
    }
    for (const ownPage of ownedPages.values()) {
      await clean(`所有ページ:${ownPage.id}`, async () => {
        expect(
          [...ownedProfiles].filter(
            ([id, parent]) => parent === ownPage.id && !deletedProfileIds.has(id),
          ),
          '未回収プロフィールの親ページは先に消さない',
        ).toHaveLength(0)
        const detail = await adminApi().get(`/api/v1/team/pages/${ownPage.id}`)
        if (detail.status() === 404) return
        expect(detail.status()).toBe(200)
        rememberPage((await detail.json()).data as OwnPage, ownPage.slug)
        const deletion = await adminApi().delete(`/api/v1/team/pages/${ownPage.id}`)
        expect(deletion.status()).toBe(204)
      })
    }
    await clean('所有ページの残存確認', async () => {
      expect((await pageList()).filter((item) => ownedSlugs.has(item.slug))).toHaveLength(0)
    })
    if (inviteId !== undefined)
      await clean(`所有招待:${inviteId}`, async () => {
        const deletion = await adminApi().delete(
          `/api/v1/organizations/${organization!.slug}/invite-tokens/${inviteId}`,
        )
        expect([204, 404]).toContain(deletion.status())
      })
    // ページcleanupに失敗した場合は先に組織を消さず、所有者が再回収できる状態を保つ。
    if (failures.length === 0)
      await clean(`所有組織:${organization.numericId}`, async () => {
        const deletion = await adminApi().delete(`/api/v1/organizations/${organization!.slug}`)
        expect(deletion.status()).toBe(204)
        const detail = await adminApi().get(`/api/v1/organizations/${organization!.slug}`)
        expect(detail.status()).toBe(404)
        organization = undefined
      })
  }
  for (const [name, api] of Object.entries(actors)) {
    await clean(`API context:${name}`, () => api.dispose())
  }
  actors = {}
  return failures
}

test.beforeAll(async () => {
  const info = test.info()
  test.setTimeout(240_000)
  try {
    for (const name of Object.keys(accounts) as ActorName[]) await loginActor(name)
    const created = await adminApi().post('/api/v1/organizations', {
      data: {
        name: `CMP1343 実機 ${uniqueSuffix}`,
        slug: `cmp1343-${uniqueSuffix}`,
        orgType: 'OTHER',
        visibility: 'PRIVATE',
      },
    })
    expect(created.status(), '専用組織作成').toBe(201)
    const createdOrganization = (await created.json()).data as { numericId: number; slug: string }
    expect(createdOrganization.slug).toBe(`cmp1343-${uniqueSuffix}`)
    expect(Number.isSafeInteger(createdOrganization.numericId)).toBe(true)
    expect(createdOrganization.numericId).toBeGreaterThan(0)
    // 要求したexact slugと有効なIDを確認できた応答だけをcleanupの所有対象へ登録する。
    organization = { numericId: createdOrganization.numericId, slug: createdOrganization.slug }
    const detail = await adminApi().get(`/api/v1/organizations/${organization.slug}`)
    expect(detail.status()).toBe(200)
    expect((await detail.json()).data.numericId).toBe(organization.numericId)
    const permissions = await adminApi().get(
      `/api/v1/organizations/${organization.slug}/me/permissions`,
    )
    expect(permissions.status()).toBe(200)
    expect((await permissions.json()).data.roleName).toBe('ADMIN')
    const initial = await adminApi().get('/api/v1/admin/member-permissions', {
      params: { scopeType: 'ORGANIZATION', scopeId: organization.numericId },
    })
    expect(initial.status()).toBe(200)
    const settings = (await initial.json()).data.permissions as { inherited: boolean }[]
    expect(settings).toHaveLength(3)
    expect(settings.every((setting) => setting.inherited)).toBe(true)
    await info.attach('initial-permissions', {
      body: Buffer.from(
        JSON.stringify({
          organization,
          count: settings.length,
          inherited: settings.map((setting) => setting.inherited),
        }),
      ),
      contentType: 'application/json',
    })
    const invite = await adminApi().post(
      `/api/v1/organizations/${organization.slug}/invite-tokens`,
      {
        data: { roleId: 4, expiresIn: '1d', maxUses: 1 },
      },
    )
    expect(invite.status()).toBe(201)
    const invitation = (await invite.json()).data as { id: number; token: string; roleName: string }
    inviteId = invitation.id
    expect(invitation.roleName).toBe('MEMBER')
    // tokenを含むURLをtransport例外/traceへ出力しない。
    let joined: APIResponse
    try {
      joined = await actors.member!.post(`/api/v1/invite/${invitation.token}/join`)
    } catch {
      throw new Error('専用組織へのMEMBER参加で通信失敗')
    }
    expect(joined.status(), '専用組織へのMEMBER参加').toBe(200)
    const member = await actors.member!.get(
      `/api/v1/organizations/${organization.slug}/me/permissions`,
    )
    expect(member.status()).toBe(200)
    expect((await member.json()).data.roleName).toBe('MEMBER')
    const outsider = await actors.outsider!.get(
      `/api/v1/organizations/${organization.slug}/me/permissions`,
    )
    if (outsider.status() === 200) expect((await outsider.json()).data.roleName).toBeNull()
    else expect([403, 404]).toContain(outsider.status())
  } catch (error) {
    const failures = await cleanupFixture()
    throw new AggregateError(
      [error, ...failures.map((label) => new Error(label))],
      'CMP1343 fixture準備失敗',
    )
  }
})

test.afterAll(async () => {
  const info = test.info()
  const ownedOrganization = organization
  const failures = await cleanupFixture()
  const owned = {
    organization: ownedOrganization,
    pageIds: [...ownedPages.keys()],
    profiles: [...ownedProfiles].map(([id, teamPageId]) => ({ id, teamPageId })),
    inviteId,
  }
  await info.attach('fixture-cleanup', {
    body: Buffer.from(
      JSON.stringify({ owned, deletedProfileIds: [...deletedProfileIds], failures }),
    ),
    contentType: 'application/json',
  })
  expect(failures, '自所有fixtureの後始末').toEqual([])
})

test.afterEach(async () => {
  const info = test.info()
  const failures: string[] = []
  for (const context of contexts) {
    if (info.status !== info.expectedStatus) {
      for (const page of context.pages()) {
        try {
          await info.attach('failure-screen', {
            body: await page.screenshot(),
            contentType: 'image/png',
          })
        } catch {
          failures.push('failure screenshot')
        }
      }
    }
    try {
      await context.close()
    } catch {
      failures.push('browser context close')
    }
  }
  contexts.clear()
  expect(failures, '全browser contextの回収').toEqual([])
})

/** 空のcontextと実ログイン結果のみで切り替える。失敗時もcontextを回収する。 */
async function openAs(browser: Browser, name: ActorName): Promise<Page> {
  const context = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    storageState: { cookies: [], origins: [] },
  })
  contexts.add(context)
  try {
    const page = await context.newPage()
    await loginViaApi(
      page,
      { email: accounts[name].email, password: password() },
      { apiBaseUrl: API_BASE_URL },
    )
    await assertIdentity(page.request, name)
    return page
  } catch {
    try {
      await context.close()
    } finally {
      contexts.delete(context)
    }
    throw new Error(`${name}のブラウザ認証・本人確認に失敗`)
  }
}

/** 本文に目的の文言が現れるまで直接待つ。 */
async function waitForBodyText(page: Page, text: string, timeout = 240_000): Promise<void> {
  await expect
    .poll(async () => (await page.locator('body').innerText()).includes(text), {
      timeout,
      message: `本文に「${text}」が現れなかった`,
    })
    .toBe(true)
}

test('① 組織メンバー紹介ページが404/エラーにならず開く（ADMIN）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, 'admin')

  const res = await page.goto(`/organizations/${ownOrganization().slug}/member-profiles`, {
    waitUntil: 'domcontentloaded',
  })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  expect(res?.status() ?? 200).toBeLessThan(400)
  await waitForBodyText(page, 'メンバー紹介')

  // ADMINには「新規ページ作成」ボタンが見えるはず
  await expect(page.getByRole('button', { name: '新規ページ作成' })).toBeVisible({
    timeout: 240_000,
  })
})

test('② MEMBERにはページ作成ボタンが見えない（操作ボタンの権限差）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, 'member')

  await page.goto(`/organizations/${ownOrganization().slug}/member-profiles`, {
    waitUntil: 'domcontentloaded',
  })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  await waitForBodyText(page, 'メンバー紹介')
  await expect(page.getByRole('button', { name: '新規ページ作成' })).toHaveCount(0)
})

test('③ ADMIN: ページ作成→メンバー追加→編集→削除→ページ削除（画面操作）', async ({
  browser,
}, info) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, 'admin')

  await page.goto(`/organizations/${ownOrganization().slug}/member-profiles`, {
    waitUntil: 'domcontentloaded',
  })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await waitForBodyText(page, 'メンバー紹介')

  // 初期設定の表示と画面証跡を確認し、通常のUI操作で閉じてからCRUDへ進む。
  await expect(page.getByTestId('member-permission-setup')).toBeVisible()
  await info.attach('initial-permission-dialog', {
    body: await page.screenshot(),
    contentType: 'image/png',
  })
  await page
    .getByTestId('member-permission-setup')
    .getByRole('button', { name: 'あとで決める', exact: true })
    .click()
  await expect(page.getByTestId('member-permission-setup')).toBeHidden()

  // --- ページ作成 ---
  await page.getByRole('button', { name: '新規ページ作成' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('input').first().fill(PAGE_TITLE) // タイトル
  const slugInput = dialog.locator('input[placeholder="members-2026"]')
  await slugInput.fill(PAGE_SLUG)
  ownedSlugs.set(PAGE_SLUG, accounts.admin.id)
  let createdPageId = 0
  await Promise.all([
    page
      .waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/team/pages' &&
          response.request().method() === 'POST',
      )
      .then(async (response) => {
        expect(response.status()).toBe(201)
        const createdPage = (await response.json()).data as OwnPage
        rememberPage(createdPage, PAGE_SLUG)
        createdPageId = createdPage.id
      }),
    dialog
      .getByRole('button', { name: /作成|保存|OK/ })
      .last()
      .click(),
  ])

  // 作成成功のトースト待ち兼ねてページ一覧に戻ってカードが出るのを待つ
  const pageCard = page.locator('.p-card', { hasText: PAGE_TITLE })
  await expect(pageCard).toBeVisible({ timeout: 30_000 })

  // --- ページを開いてメンバー追加 ---
  await pageCard.getByRole('button', { name: 'メンバーを見る' }).click()
  await expect(page.getByRole('heading', { name: PAGE_TITLE })).toBeVisible({ timeout: 30_000 })

  await page.getByRole('button', { name: 'メンバー追加' }).click()
  const memberDialog = page.getByRole('dialog')
  await memberDialog.locator('input').first().fill(MEMBER_NAME)
  let createdProfileId = 0
  await Promise.all([
    page
      .waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/team/members' &&
          response.request().method() === 'POST',
      )
      .then(async (response) => {
        expect(response.status()).toBe(201)
        const addedMember = (await response.json()).data as { id: number; teamPageId: number }
        expect(addedMember.teamPageId).toBe(
          [...ownedPages.values()].find((item) => item.slug === PAGE_SLUG)?.id,
        )
        expect(Number.isSafeInteger(addedMember.id)).toBe(true)
        expect(addedMember.id).toBeGreaterThan(0)
        ownedProfiles.set(addedMember.id, addedMember.teamPageId)
        createdProfileId = addedMember.id
      }),
    memberDialog
      .getByRole('button', { name: /追加|保存|OK/ })
      .last()
      .click(),
  ])

  const memberCard = page.getByText(MEMBER_NAME, { exact: false })
  await expect(memberCard.first()).toBeVisible({ timeout: 30_000 })

  // --- 編集 ---
  const editButton = page.locator('[aria-label="編集"], button:has(.pi-pencil)').first()
  await editButton.click()
  const editDialog = page.getByRole('dialog')
  const nameInput = editDialog.locator('input').first()
  await nameInput.fill(MEMBER_NAME_EDITED)
  await editDialog
    .getByRole('button', { name: /保存|更新|OK/ })
    .last()
    .click()
  await expect(page.getByText(MEMBER_NAME_EDITED, { exact: false }).first()).toBeVisible({
    timeout: 30_000,
  })

  // --- 削除 ---
  const deleteButton = page.locator('button:has(.pi-trash)').first()
  await deleteButton.click()
  const memberDeleteDialog = page.getByTestId('member-delete-confirm-dialog')
  await expect(memberDeleteDialog).toBeVisible()
  await Promise.all([
    page
      .waitForResponse(
        (response) =>
          new URL(response.url()).pathname === `/api/v1/team/members/${createdProfileId}` &&
          response.request().method() === 'DELETE',
      )
      .then((response) => {
        expect(response.status()).toBe(204)
        deletedProfileIds.add(createdProfileId)
      }),
    memberDeleteDialog.getByRole('button', { name: '削除', exact: true }).click(),
  ])
  await expect(page.getByText(MEMBER_NAME_EDITED, { exact: false })).toHaveCount(0, {
    timeout: 30_000,
  })

  // --- ページ削除（後始末を画面操作でも行う） ---
  await page.getByRole('button', { name: 'ページ一覧に戻る' }).click()
  const deletePageButton = page
    .locator('.p-card', { hasText: PAGE_TITLE })
    .locator('button:has(.pi-trash)')
  await deletePageButton.click()
  const deletePageConfirm = page.getByRole('dialog')
  await expect(deletePageConfirm).toBeVisible()
  await Promise.all([
    page
      .waitForResponse(
        (response) =>
          new URL(response.url()).pathname === `/api/v1/team/pages/${createdPageId}` &&
          response.request().method() === 'DELETE',
      )
      .then((response) => {
        expect(response.status()).toBe(204)
      }),
    deletePageConfirm.getByRole('button', { name: '削除', exact: true }).click(),
  ])
  await expect(page.locator('.p-card', { hasText: PAGE_TITLE })).toHaveCount(0, { timeout: 30_000 })
})

test('④ URL直打ちでもMEMBERはページ作成APIに書き込めない（権限横断）', async () => {
  test.setTimeout(60_000)
  const slug = `cmp1343-denied-${uniqueSuffix}`
  // 認可に回帰があって作成されても、この試行のexact slugだけをcleanup対象にする。
  ownedSlugs.set(slug, accounts.member.id)
  const response = await actors.member!.post('/api/v1/team/pages', {
    data: {
      organizationId: ownOrganization().numericId,
      title: `不正作成試行${uniqueSuffix}`,
      slug,
      pageType: 'YEARLY',
      year: new Date().getFullYear(),
      visibility: 'MEMBERS_ONLY',
    },
  })
  expect([403, 404]).toContain(response.status())
  expect((await pageList()).filter((item) => item.slug === slug)).toHaveLength(0)
})

test('⑤ 他テナント(非所属)は組織メンバー紹介ページのAPIにアクセスできない', async () => {
  test.setTimeout(60_000)
  const slug = `cmp1343-private-${uniqueSuffix}`
  ownedSlugs.set(slug, accounts.admin.id)
  // MAIN検体にして③のYEARLY/current yearと衝突させない。対象操作ではなく認可APIの前提。
  const response = await adminApi().post('/api/v1/team/pages', {
    data: {
      organizationId: ownOrganization().numericId,
      title: `非所属確認用${uniqueSuffix}`,
      slug,
      pageType: 'MAIN',
      visibility: 'MEMBERS_ONLY',
    },
  })
  expect(response.status()).toBe(201)
  const created = (await response.json()).data as OwnPage
  rememberPage(created, slug)
  const published = await adminApi().patch(`/api/v1/team/pages/${created.id}/publish`, {
    data: { status: 'PUBLISHED' },
  })
  expect(published.status()).toBe(200)
  const actual = await adminApi().get(`/api/v1/team/pages/${created.id}`)
  expect(actual.status()).toBe(200)
  const protectedPage = (await actual.json()).data as OwnPage
  rememberPage(protectedPage, slug)
  expect(protectedPage.visibility).toBe('MEMBERS_ONLY')
  expect(protectedPage.status).toBe('PUBLISHED')

  const list = await actors.outsider!.get('/api/v1/team/pages', {
    params: { organizationId: ownOrganization().numericId, size: 100 },
  })
  expect([403, 404], '既定MEMBER門は非所属の一覧を拒否').toContain(list.status())
  const detail = await actors.outsider!.get(`/api/v1/team/pages/${created.id}`)
  expect([403, 404], '保護対象IDの直アクセスも拒否').toContain(detail.status())
  const deletion = await adminApi().delete(`/api/v1/team/pages/${created.id}`)
  expect(deletion.status()).toBe(204)
})
