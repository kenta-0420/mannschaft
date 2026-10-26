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
type Scope = { id: number; name: string; role?: string }
let api: APIRequestContext
let orgId: number
let teamId: number
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
  const organizations = await api.get('/api/v1/me/organizations?size=100')
  expect(organizations.status()).toBe(200)
  const orgs = (await organizations.json()).data as Scope[]
  const organization = process.env.WAVE3_ORG_ID
    ? orgs.find((org) => org.id === Number(process.env.WAVE3_ORG_ID))
    : orgs.find((org) => org.name.includes('日本サッカー'))
  expect(organization, 'canonical seedのJFA組織').toBeTruthy()
  orgId = organization!.id
  const teams = await api.get('/api/v1/me/teams')
  expect(teams.status()).toBe(200)
  const team = ((await teams.json()).data as Scope[]).find((item) =>
    item.name.includes('FC東京U-18'),
  )
  expect(team, 'canonical seedの管理対象チーム').toBeTruthy()
  teamId = team!.id

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
})

test.afterAll(async () => {
  if (!api) return
  try {
    for (const id of templateIds) {
      const response = await api.delete(`${templateApi()}/${id}`)
      expect(response.status(), '所有テンプレートcleanup').toBe(204)
    }
    if (tournamentId) {
      const response = await api.delete(tournamentApi())
      expect(response.status(), '所有大会cleanup').toBe(204)
    }
  } finally {
    await api.dispose()
  }
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
    await expect(dialog).toBeVisible()

    await dialog.getByRole('combobox').click()
    await page.getByRole('option', { name: `${prefix}-${locale}`, exact: true }).click()
    const removed = await api.delete(`${templateApi()}/${templateId}`)
    expect(removed.status()).toBe(204)
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
