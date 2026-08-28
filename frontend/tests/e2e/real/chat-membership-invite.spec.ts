import {
  expect,
  request,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const PASSWORD = 'TestPass2026!'
const ISSUER = {
  email: 'e2e-user@test.mannschaft.local',
  password: PASSWORD,
  dmPartnerName: 'E2E管理者',
}
const TARGET = {
  email: 'e2e-admin@test.mannschaft.local',
  password: PASSWORD,
  dmPartnerName: '検証テスト名',
}

interface ScopeFixture {
  id: number
  slug: string
  name: string
}

async function login(page: Page, user: { email: string; password: string }): Promise<void> {
  await page.context().clearCookies()
  await loginViaApi(page, user, { apiBaseUrl: API_BASE })
}

async function openDm(page: Page, partnerName: string): Promise<void> {
  await page.goto('/chat')
  await waitForHydration(page)

  const channel = page.locator('aside').getByText(partnerName, { exact: false }).first()
  await expect(channel).toBeVisible({ timeout: 20_000 })
  await channel.click()
  await expect(page.locator('[data-tab-active="true"] [data-testid="team-chat-input"]'))
    .toBeVisible({ timeout: 15_000 })
}

async function issueFromUi(page: Page, scope: ScopeFixture): Promise<void> {
  await page.locator('[data-tab-active="true"] [data-testid="chat-membership-invite-btn"]').click()

  const option = page
    .locator('[data-testid="chat-invite-scope-option"]')
    .filter({ hasText: scope.name })
  await expect(option).toHaveCount(1, { timeout: 15_000 })
  await option.click()

  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && /\/api\/v1\/chat\/channels\/\d+\/membership-invite$/.test(response.url()),
  )
  await page.getByTestId('chat-invite-submit').click()
  const response = await responsePromise
  expect(response.status()).toBe(201)

  const body = await response.json() as {
    data: { tokenId: number; cardMessageId: number; scopeType: string; scopeId: number }
  }
  expect(body.data.tokenId).toBeGreaterThan(0)
  expect(body.data.cardMessageId).toBeGreaterThan(0)
  expect(body.data.scopeId).toBe(scope.id)

  const card = page.getByTestId('chat-invite-card').filter({ hasText: scope.name }).last()
  await expect(card).toBeVisible({ timeout: 15_000 })
  await expect(card.getByTestId('chat-invite-status')).toContainText('承諾待ち')
  await expect(card.getByTestId('chat-invite-join')).toHaveCount(0)
}

async function createFixtures(api: APIRequestContext): Promise<{
  team: ScopeFixture
  organization: ScopeFixture
}> {
  const suffix = Date.now()

  const teamName = `E2E承諾招待チーム${suffix}`
  const teamSlug = `e2e-invite-team-${suffix}`.slice(0, 30)
  const teamResponse = await api.post('/api/v1/teams', {
    data: {
      name: teamName,
      slug: teamSlug,
      visibility: 'MEMBERS_AND_ABOVE',
    },
  })
  expect(teamResponse.status(), await teamResponse.text()).toBe(201)
  const teamBody = await teamResponse.json() as { data: { numericId: number; slug: string } }

  const orgName = `E2E辞退招待組織${suffix}`
  const orgSlug = `e2e-invite-org-${suffix}`.slice(0, 30)
  const orgResponse = await api.post('/api/v1/organizations', {
    data: {
      name: orgName,
      slug: orgSlug,
      orgType: 'OTHER',
      visibility: 'PRIVATE',
    },
  })
  expect(orgResponse.status(), await orgResponse.text()).toBe(201)
  const orgBody = await orgResponse.json() as { data: { numericId: number; slug: string } }

  return {
    team: { id: teamBody.data.numericId, slug: teamBody.data.slug, name: teamName },
    organization: { id: orgBody.data.numericId, slug: orgBody.data.slug, name: orgName },
  }
}

test.describe('F04.12 チャット承諾型招待 実機E2E', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(150_000)

  let issuerApi: APIRequestContext
  let targetApi: APIRequestContext
  let team: ScopeFixture
  let organization: ScopeFixture

  test.beforeAll(async () => {
    issuerApi = await request.newContext({ baseURL: API_BASE })
    targetApi = await request.newContext({ baseURL: API_BASE })

    const issuerLogin = await issuerApi.post('/api/v1/auth/login', { data: ISSUER })
    expect(issuerLogin.status(), await issuerLogin.text()).toBe(200)
    const targetLogin = await targetApi.post('/api/v1/auth/login', { data: TARGET })
    expect(targetLogin.status(), await targetLogin.text()).toBe(200)

    const targetMe = await targetApi.get('/api/v1/users/me')
    expect(targetMe.status()).toBe(200)
    const targetId = ((await targetMe.json()) as { data: { id: number } }).data.id
    const dm = await issuerApi.post('/api/v1/chat/channels/conversations', {
      data: { userIds: [targetId] },
    })
    expect([200, 201]).toContain(dm.status())

    const fixtures = await createFixtures(issuerApi)
    team = fixtures.team
    organization = fixtures.organization
  })

  test.afterAll(async () => {
    if (team?.slug) {
      const response = await issuerApi.delete(`/api/v1/teams/${team.slug}`)
      expect(response.status(), await response.text()).toBe(204)
    }
    if (organization?.slug) {
      const response = await issuerApi.delete(`/api/v1/organizations/${organization.slug}`)
      expect(response.status(), await response.text()).toBe(204)
    }
    await issuerApi?.dispose()
    await targetApi?.dispose()
  })

  test('INVITE-REAL-001: DMからチーム招待を発行し、宛先本人が承諾すると再読込後も所属が残る', async ({ page }) => {
    await login(page, ISSUER)
    await openDm(page, ISSUER.dmPartnerName)
    await issueFromUi(page, team)

    await page.reload()
    await waitForHydration(page)
    await openDm(page, ISSUER.dmPartnerName)
    await expect(page.getByTestId('chat-invite-card').filter({ hasText: team.name }).last())
      .toBeVisible({ timeout: 15_000 })

    await login(page, TARGET)
    await openDm(page, TARGET.dmPartnerName)
    const targetCard = page.getByTestId('chat-invite-card').filter({ hasText: team.name }).last()
    await expect(targetCard.getByTestId('chat-invite-join')).toBeVisible()

    const joinResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'POST' && /\/api\/v1\/invite\/[^/]+\/join$/.test(response.url()),
    )
    await targetCard.getByTestId('chat-invite-join').click()
    const joinResponse = await joinResponsePromise
    expect(joinResponse.status(), await joinResponse.text()).toBe(200)
    await expect(targetCard.getByTestId('chat-invite-status')).toContainText('参加済み')
    await expect(targetCard.getByTestId('chat-invite-join')).toHaveCount(0)

    await page.reload()
    await waitForHydration(page)
    await openDm(page, TARGET.dmPartnerName)
    const persistedCard = page.getByTestId('chat-invite-card').filter({ hasText: team.name }).last()
    await expect(persistedCard.getByTestId('chat-invite-status')).toContainText('参加済み')

    const members = await targetApi.get(`/api/v1/teams/${team.slug}/members?page=0&size=100`)
    expect(members.status(), await members.text()).toBe(200)
    const memberBody = await members.json() as { data: Array<{ userId: number }> }
    expect(memberBody.data.some((member) => member.userId === 24)).toBe(true)
  })

  test('INVITE-REAL-002: 組織招待を宛先本人が辞退すると再読込後も辞退済みで、所属は増えない', async ({ page }) => {
    await login(page, ISSUER)
    await openDm(page, ISSUER.dmPartnerName)
    await issueFromUi(page, organization)

    await login(page, TARGET)
    await openDm(page, TARGET.dmPartnerName)
    const targetCard = page
      .getByTestId('chat-invite-card')
      .filter({ hasText: organization.name })
      .last()
    await expect(targetCard.getByTestId('chat-invite-decline')).toBeVisible()

    const declineResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'POST'
        && /\/api\/v1\/invite\/[^/]+\/decline$/.test(response.url()),
    )
    await targetCard.getByTestId('chat-invite-decline').click()
    const declineResponse = await declineResponsePromise
    expect(declineResponse.status(), await declineResponse.text()).toBe(200)
    await expect(targetCard.getByTestId('chat-invite-status')).toContainText('辞退済み')
    await expect(targetCard.getByTestId('chat-invite-join')).toHaveCount(0)

    await page.reload()
    await waitForHydration(page)
    await openDm(page, TARGET.dmPartnerName)
    const persistedCard = page
      .getByTestId('chat-invite-card')
      .filter({ hasText: organization.name })
      .last()
    await expect(persistedCard.getByTestId('chat-invite-status')).toContainText('辞退済み')

    const members = await issuerApi.get(
      `/api/v1/organizations/${organization.slug}/members?page=0&size=100`,
    )
    expect(members.status(), await members.text()).toBe(200)
    const memberBody = await members.json() as { data: Array<{ userId: number }> }
    expect(memberBody.data.some((member) => member.userId === 24)).toBe(false)
  })
})
