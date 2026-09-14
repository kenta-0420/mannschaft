import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

test.describe.configure({ mode: 'serial' })

const apiBaseUrl = process.env.API_BASE_URL
const parentOrgSlug = process.env.E2E_PARENT_ORG_SLUG
const adminEmail = process.env.TEST_ADMIN_EMAIL
const adminPassword = process.env.TEST_ADMIN_PASSWORD
const memberEmail = process.env.TEST_USER_EMAIL
const memberPassword = process.env.TEST_USER_PASSWORD

function required(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} is required; provide it via environment variables.`)
  return value
}

type ApiErrorBody = { code?: string; errorCode?: string; error?: { code?: string; errorCode?: string } }

function assertTimeline018(body: ApiErrorBody, label: string): void {
  const code = body.code ?? body.errorCode ?? body.error?.code ?? body.error?.errorCode
  expect(code ?? JSON.stringify(body), label).toContain('TIMELINE_018')
}

async function openPersonalFeed(page: Page, marker: string): Promise<void> {
  const feedResponse = page.waitForResponse(
    response => response.url().includes('/api/v1/timeline/my')
      && response.request().method() === 'GET',
  )
  await page.goto('/')
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  expect((await feedResponse).status()).toBe(200)
  await expect(page.locator('#scope-panel-PERSONAL').getByText(marker, { exact: true }))
    .toBeVisible({ timeout: 30_000 })
}

async function openMuteMenuFor(page: Page, marker: string): Promise<void> {
  const card = page.locator('[data-testid="team-timeline-post"]')
    .filter({ hasText: marker })
    .filter({ has: page.getByTestId('team-timeline-post-menu') })
    .last()
  await expect(card).toBeVisible()
  await card.getByTestId('team-timeline-post-menu').click()
}

test('CMP-101: ORGタイムライン投稿のミュート・解除と不正種別/未認証API', async ({ browser }) => {
  test.setTimeout(600_000)
  const baseUrl = required('API_BASE_URL', apiBaseUrl).replace(/\/$/, '')
  const organizationSlug = required('E2E_PARENT_ORG_SLUG', parentOrgSlug)
  const marker = `CMP101-${Date.now()}`
  const admin: BrowserContext = await browser.newContext()
  const member: BrowserContext = await browser.newContext()
  const anonymous: BrowserContext = await browser.newContext()
  let postId: number | undefined
  let organizationId: number | undefined
  let muted = false
  let completed = false

  try {
    const adminPage = await admin.newPage()
    await loginViaApi(adminPage, {
      email: required('TEST_ADMIN_EMAIL', adminEmail),
      password: required('TEST_ADMIN_PASSWORD', adminPassword),
    })
    const organizationResponse = await adminPage.request.get(
      `${baseUrl}/api/v1/organizations/${organizationSlug}`,
    )
    expect(organizationResponse.status()).toBe(200)
    const organizationBody = await organizationResponse.json() as {
      data?: { numericId?: number }
      numericId?: number
    }
    organizationId = organizationBody.data?.numericId ?? organizationBody.numericId
    if (organizationId === undefined) throw new Error('組織IDを取得できませんでした')

    await adminPage.goto(`/organizations/${organizationSlug}/timeline`)
    await waitForHydration(adminPage)
    await expect(adminPage.getByTestId('team-timeline-composer')).toBeVisible({ timeout: 90_000 })
    await adminPage.getByTestId('team-timeline-composer').fill(marker)
    const created = adminPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/posts')
        && response.request().method() === 'POST',
    )
    await adminPage.getByTestId('team-timeline-submit').click()
    const createdResponse = await created
    expect(createdResponse.status()).toBe(201)
    const createdBody = await createdResponse.json() as { data?: { id?: number }; id?: number }
    postId = createdBody.data?.id ?? createdBody.id
    if (postId === undefined) throw new Error('投稿IDを取得できませんでした')

    const memberPage = await member.newPage()
    await loginViaApi(memberPage, {
      email: required('TEST_USER_EMAIL', memberEmail),
      password: required('TEST_USER_PASSWORD', memberPassword),
    })
    await openPersonalFeed(memberPage, marker)

    await openMuteMenuFor(memberPage, marker)
    const mutedResponse = memberPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/mutes')
        && response.request().method() === 'POST',
    )
    await memberPage.getByRole('menuitem', { name: 'この団体の投稿を非表示にする', exact: true }).click()
    expect((await mutedResponse).status()).toBe(201)
    muted = true
    await expect(memberPage.getByText(marker, { exact: true })).toHaveCount(0, { timeout: 15_000 })

    const chip = memberPage.getByTestId('timeline-muted-chip')
    await expect(chip).toBeVisible({ timeout: 15_000 })
    await expect(chip).toContainText('非表示中')
    await chip.click()
    await expect(memberPage.getByText('非表示にしている相手', { exact: true })).toBeVisible()
    const unmutedResponse = memberPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/mutes')
        && response.request().method() === 'DELETE',
    )
    await memberPage.getByTestId('timeline-unmute-button').click()
    expect((await unmutedResponse).status()).toBe(204)
    muted = false
    await expect(memberPage.getByTestId('timeline-muted-chip')).toHaveCount(0)
    const reloadedFeedResponse = memberPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/my')
        && response.request().method() === 'GET',
    )
    await memberPage.reload({ waitUntil: 'domcontentloaded' })
    await waitForHydration(memberPage)
    await waitForSpinnerGone(memberPage)
    expect((await reloadedFeedResponse).status()).toBe(200)
    await expect(memberPage.locator('#scope-panel-PERSONAL').getByText(marker, { exact: true }))
      .toBeVisible({ timeout: 30_000 })

    for (const mutedType of ['USER', 'SOCIAL_PROFILE', 'team', 'UNKNOWN']) {
      const invalid = await memberPage.request.post(`${baseUrl}/api/v1/timeline/mutes`, {
        data: { mutedType, mutedId: organizationId },
        headers: { 'Content-Type': 'application/json' },
      })
      expect(invalid.status(), `mutedType=${mutedType}`).toBe(400)
      assertTimeline018(await invalid.json() as ApiErrorBody, `mutedType=${mutedType}`)
    }

    for (const mutedType of ['', null]) {
      const invalid = await memberPage.request.post(`${baseUrl}/api/v1/timeline/mutes`, {
        data: { mutedType, mutedId: organizationId },
        headers: { 'Content-Type': 'application/json' },
      })
      expect(invalid.status(), `mutedType=${String(mutedType)}`).toBe(400)
    }

    const unauthenticated = await (await anonymous.newPage()).request.post(
      `${baseUrl}/api/v1/timeline/mutes`, {
        data: { mutedType: 'ORGANIZATION', mutedId: organizationId },
        headers: { 'Content-Type': 'application/json' },
      },
    )
    expect(unauthenticated.status(), '未認証のミュート追加').toBe(401)

    // 連打時の重複POST仕様は本テストで決めず、UIの単一操作とAPIエラー契約を証拠として確認する。
    completed = true
  } finally {
    const cleanupErrors: unknown[] = []
    if (muted && organizationId !== undefined) {
      try {
        const response = await member.request.delete(
          `${baseUrl}/api/v1/timeline/mutes?mutedType=ORGANIZATION&mutedId=${organizationId}`,
        )
        if (![204, 404].includes(response.status())) cleanupErrors.push(new Error(`ミュートcleanup失敗: HTTP ${response.status()}`))
      } catch (error) { cleanupErrors.push(error) }
    }
    if (postId !== undefined) {
      try {
        const response = await admin.request.delete(`${baseUrl}/api/v1/timeline/posts/${postId}`)
        if (![200, 204, 404].includes(response.status())) cleanupErrors.push(new Error(`投稿cleanup失敗: HTTP ${response.status()}`))
      } catch (error) { cleanupErrors.push(error) }
    }
    await Promise.allSettled([admin.close(), member.close(), anonymous.close()])
    if (completed && cleanupErrors.length > 0) throw cleanupErrors[0]
    cleanupErrors.forEach(error => console.error('CMP-101 cleanup failed', error))
  }
})
