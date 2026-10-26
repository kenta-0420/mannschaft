/**
 * CMP-261002-0743 T4/T5: 実BE・実UIで保存/テンプレート適用の失敗を確認する。
 * 全10コード×6言語はunit契約試験、認可のBE拒否は既存認可ITが担当する。
 * APIは専用大会・テンプレートfixture、ログイン、後始末だけに使う。応答stubなし。
 */
import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'
import { tournamentErrorTranslations } from '../../../unit/helpers/tournamentErrorContract'
import ja from '../../../../app/locales/ja/tournament.json' with { type: 'json' }
import en from '../../../../app/locales/en/tournament.json' with { type: 'json' }
import zh from '../../../../app/locales/zh/tournament.json' with { type: 'json' }
import ko from '../../../../app/locales/ko/tournament.json' with { type: 'json' }
import es from '../../../../app/locales/es/tournament.json' with { type: 'json' }
import de from '../../../../app/locales/de/tournament.json' with { type: 'json' }

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial', timeout: 120_000 })
const API = process.env.API_BASE_URL ?? 'http://localhost:8083'
const BASE = process.env.BASE_URL ?? 'http://localhost:3002'
const adminEmail = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const password = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'
const messages = { ja, en, zh, ko, es, de }
type Locale = keyof typeof messages
let api: APIRequestContext
let orgId: number
let teamId: number
let orgSlug: string | undefined
let teamSlug: string | undefined
let affiliationActive = false
let tournamentId: number | undefined
let divisionId: number
let participantId: number
const templateIds = new Set<string>()
const prefix = `CMP0743-${Date.now()}`
const tournamentApi = () => `/api/v1/organizations/${orgId}/tournaments/${tournamentId}`
const entryApi = () =>
  `${tournamentApi()}/divisions/${divisionId}/participants/${participantId}/entry-members`
const templateApi = () => `/api/v1/organizations/${orgId}/teams/${teamId}/entry-templates`
const detailPath = () => `/organizations/${orgId}/tournaments/${tournamentId}`

test.beforeAll(async () => {
  api = await request.newContext({ baseURL: API })
  const login = await api.post('/api/v1/auth/login', { data: { email: adminEmail, password } })
  expect(login.status(), 'fixture管理者ログイン').toBe(200)
  // seedの親組織と直接加盟先は異なるため、所有fixtureだけを正規APIで作成する。
  const organization = await api.post('/api/v1/organizations', {
    data: {
      name: `${prefix}-org`,
      slug: `cmp0743-o-${Date.now()}`,
      orgType: 'OTHER',
      visibility: 'PUBLIC',
    },
  })
  expect(organization.status()).toBe(201)
  const org = (await organization.json()).data as { numericId: number; slug: string }
  orgId = org.numericId
  orgSlug = org.slug
  expect(orgId).toBeGreaterThan(0)
  const team = await api.post('/api/v1/teams', {
    data: {
      name: `${prefix}-team`,
      slug: `cmp0743-t-${Date.now()}`,
      template: 'SPORTS',
      visibility: 'PUBLIC',
    },
  })
  expect(team.status()).toBe(201)
  const teamData = (await team.json()).data as { numericId: number; slug: string }
  teamId = teamData.numericId
  teamSlug = teamData.slug
  expect(teamId).toBeGreaterThan(0)
  const invite = await api.post(`/api/v1/organizations/${orgSlug}/team-invites`, {
    data: { teamSlug },
  })
  expect(invite.status()).toBe(201)
  const membershipId = (await invite.json()).data.id as number
  const accept = await api.post(`/api/v1/teams/${teamSlug}/org-invites/${membershipId}/accept`)
  expect(accept.status()).toBe(200)
  expect((await accept.json()).data.status).toBe('ACTIVE')
  affiliationActive = true

  const tournament = await api.post(`/api/v1/organizations/${orgId}/tournaments`, {
    data: { name: prefix, format: 'LEAGUE', visibility: 'PUBLIC', sport: 'SOCCER' },
  })
  expect(tournament.status()).toBe(201)
  tournamentId = (await tournament.json()).data.id as number
  const division = await api.post(`${tournamentApi()}/divisions`, {
    data: { name: `${prefix}-division`, minEntryCount: 1, maxEntryCount: 10 },
  })
  expect(division.status()).toBe(201)
  divisionId = (await division.json()).data.id as number
  const participant = await api.post(`${tournamentApi()}/divisions/${divisionId}/participants`, {
    data: { teamId },
  })
  expect(participant.status()).toBe(201)
  participantId = (await participant.json()).data.id as number
  console.log(
    'CMP0743 owned fixture',
    JSON.stringify({
      prefix,
      orgId,
      orgSlug,
      teamId,
      teamSlug,
      tournamentId,
      divisionId,
      participantId,
    }),
  )
})

test.afterAll(async () => {
  if (!api) return
  const failures: string[] = []
  const clean = async (name: string, run: () => Promise<void>) => {
    try {
      await run()
    } catch (error) {
      failures.push(`${name}: ${String(error)}`)
    }
  }
  try {
    for (const id of templateIds) {
      await clean(`template ${id}`, async () => {
        const response = await api.delete(`${templateApi()}/${id}`)
        expect(response.status(), '所有テンプレートcleanup').toBe(204)
        expect((await api.get(`${templateApi()}/${id}`)).status()).toBe(404)
        console.log('CMP0743 cleanup template', id, '204->404')
      })
    }
    if (tournamentId) {
      await clean('tournament', async () => {
        const response = await api.delete(tournamentApi())
        expect(response.status(), '所有大会cleanup').toBe(204)
        expect((await api.get(tournamentApi())).status()).toBe(404)
        console.log('CMP0743 cleanup tournament', tournamentId, '204->404')
      })
    }
    if (affiliationActive)
      await clean('affiliation', async () => {
        const path = `/api/v1/teams/${teamSlug}/organizations/${orgSlug}`
        expect((await api.delete(path)).status()).toBe(204)
        expect((await api.delete(path)).status()).toBe(404)
        console.log('CMP0743 cleanup affiliation', teamId, orgId, '204->404')
      })
    if (teamSlug)
      await clean('team', async () => {
        const path = `/api/v1/teams/${teamSlug}`
        expect((await api.delete(path)).status()).toBe(204)
        expect((await api.get(path)).status()).toBe(404)
        console.log('CMP0743 cleanup team', teamId, '204->404')
      })
    if (orgSlug)
      await clean('organization', async () => {
        const path = `/api/v1/organizations/${orgSlug}`
        expect((await api.delete(path)).status()).toBe(204)
        expect((await api.get(path)).status()).toBe(404)
        console.log('CMP0743 cleanup organization', orgId, '204->404')
      })
  } finally {
    await api.dispose()
  }
  expect(failures, '所有fixtureの後始末を全件確認').toEqual([])
})

async function open(page: Page, locale: Locale, email = adminEmail) {
  await loginViaApi(page, { email, password }, { apiBaseUrl: API, deferNavigation: true })
  // 当該テストブラウザだけの表示言語fixture。共有ユーザーの設定/権限は変更しない。
  await page.addInitScript((language) => {
    const current = JSON.parse(localStorage.getItem('currentUser') ?? '{}') as Record<
      string,
      unknown
    >
    current.locale = language
    localStorage.setItem('currentUser', JSON.stringify(current))
  }, locale)
  await page.context().addCookies([{ name: 'i18n_locale', value: locale, url: BASE }])
  await page.goto(`${BASE}${detailPath()}`, { waitUntil: 'domcontentloaded' })
  await expect(page.getByTestId('tournament-detail-title')).toHaveText(prefix, { timeout: 60_000 })
}

for (const locale of Object.keys(messages) as Locale[]) {
  test(`T4 ${locale}: UI保存の最少人数不足と削除済みテンプレート適用を翻訳し成功を出さない`, async ({
    page,
  }) => {
    const template = await api.post(templateApi(), {
      data: { name: `${prefix}-${locale}`, members: [] },
    })
    expect(template.status()).toBe(201)
    const templateId = (await template.json()).data.id as string
    templateIds.add(templateId)
    const text = messages[locale].tournament
    await open(page, locale)
    await page.getByRole('button', { name: text.detail.manageEntry, exact: true }).click()
    const dialog = page.getByRole('dialog', { name: text.entry.title })
    await expect(dialog).toBeVisible()
    await expect(
      dialog.getByRole('button', { name: text.entry.loadFromTeam, exact: true }),
    ).toBeVisible()

    const save = page.waitForResponse(
      (response) => response.url().endsWith(entryApi()) && response.request().method() === 'PUT',
    )
    await dialog.getByRole('button', { name: text.entry.saveMembers, exact: true }).click()
    const saveResponse = await save
    expect(saveResponse.status()).toBe(422)
    expect((await saveResponse.json()).error.code).toBe('TOUR_022')
    await expect(
      page.getByText(tournamentErrorTranslations[locale][3], { exact: true }).first(),
    ).toBeVisible()
    await expect(page.getByText(text.entry.saveSuccess, { exact: true })).toHaveCount(0)
    await expect(page.locator('.p-toast-message-success')).toHaveCount(0)
    await expect(dialog).toBeVisible()

    await dialog.getByRole('combobox').click()
    await page.getByRole('option', { name: `${prefix}-${locale}`, exact: true }).click()
    const removed = await api.delete(`${templateApi()}/${templateId}`)
    expect(removed.status()).toBe(204)
    expect((await api.get(`${templateApi()}/${templateId}`)).status()).toBe(404)
    console.log('CMP0743 cleanup template', templateId, '204->404')
    templateIds.delete(templateId)
    const apply = page.waitForResponse(
      (response) =>
        response.url().endsWith(`${entryApi()}/apply-template`) &&
        response.request().method() === 'POST',
    )
    await dialog.getByRole('button', { name: text.entry.template.apply, exact: true }).click()
    const applyResponse = await apply
    expect(applyResponse.status()).toBe(404)
    expect((await applyResponse.json()).error.code).toBe('TOUR_024')
    await expect(
      page.getByText(tournamentErrorTranslations[locale][5], { exact: true }).first(),
    ).toBeVisible()
    await expect(dialog).toBeVisible()
    await expect(page.getByText(text.entry.saveSuccess, { exact: true })).toHaveCount(0)
    await expect(page.locator('.p-toast-message-success')).toHaveCount(0)
    await expect(dialog.locator('input[type="checkbox"]:checked')).toHaveCount(0)
  })
}

for (const [role, email] of [
  ['一般会員', process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'],
  ['非所属ユーザー', process.env.TEST_OUTSIDER_EMAIL ?? 'e2e-outsider@test.mannschaft.local'],
] as const) {
  test(`T5 ${role}: 直URLの公開大会でもエントリー編集操作を表示しない`, async ({ page }) => {
    await open(page, 'ja', email)
    await expect(
      page.getByRole('button', { name: messages.ja.tournament.detail.manageEntry, exact: true }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('dialog', { name: messages.ja.tournament.entry.title }),
    ).toHaveCount(0)
  })
}

test('T5 未認証の直URLはログインへ遷移する', async ({ page }) => {
  await page.goto(`${BASE}${detailPath()}`, { waitUntil: 'domcontentloaded' })
  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(
    page.getByRole('button', { name: messages.ja.tournament.detail.manageEntry, exact: true }),
  ).toHaveCount(0)
})
