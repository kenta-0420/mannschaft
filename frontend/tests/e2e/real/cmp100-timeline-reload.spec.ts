import { test, expect, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

test.describe.configure({ mode: 'serial' })

const adminEmail = process.env.TEST_ADMIN_EMAIL
const adminPassword = process.env.TEST_ADMIN_PASSWORD
const memberEmail = process.env.TEST_USER_EMAIL
const memberPassword = process.env.TEST_USER_PASSWORD
const outsiderEmail = process.env.TEST_OUTSIDER_EMAIL
const outsiderPassword = process.env.TEST_OUTSIDER_PASSWORD
const parentOrgSlug = process.env.E2E_PARENT_ORG_SLUG
const apiBaseUrl = process.env.API_BASE_URL
const externalRemoval = process.env.CMP100_EXTERNAL_MEMBERSHIP_REMOVAL === '1'

function required(name: string, value: string | undefined): string {
  if (!value) throw new Error(`${name} is required; provide it via environment variables.`)
  return value
}

async function openPersonalFeed(page: Page, marker: string): Promise<Locator> {
  const feedResponse = page.waitForResponse(
    response => response.url().includes('/api/v1/timeline/my')
      && response.request().method() === 'GET',
  )
  await page.goto('/')
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  const response = await feedResponse
  expect(response.status()).toBe(200)
  const personalFeed = page.locator('#scope-panel-PERSONAL')
  await expect(personalFeed.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
  return personalFeed
}

async function assertDetailDenied(page: Page, postId: number, marker: string) {
  const detailResponse = page.waitForResponse(
    response => response.url().includes(`/api/v1/timeline/posts/${postId}`)
      && response.request().method() === 'GET',
  )
  await page.goto(`/timeline/${postId}`)
  expect((await detailResponse).status()).toBe(404)
  await expect(page.getByText(marker, { exact: true })).toHaveCount(0)
}

async function openPostDetail(page: Page, permalink: Locator, postId: number, marker: string) {
  const detailResponse = page.waitForResponse(
    response => response.url().includes(`/api/v1/timeline/posts/${postId}`)
      && response.request().method() === 'GET',
  )
  await permalink.click()
  expect((await detailResponse).status()).toBe(200)
  await expect(page).toHaveURL(new RegExp(`/timeline/${postId}$`))
  await expect(page.locator('#scope-panel-PERSONAL')).toHaveCount(0)
  await expect(page.getByText(marker, { exact: true })).toBeVisible()
}

async function returnToPersonalFeed(page: Page, personalFeed: Locator, marker: string) {
  await page.goBack({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await expect(personalFeed.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
}

async function reloadPersonalFeed(page: Page, personalFeed: Locator): Promise<void> {
  await page.reload({ waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(personalFeed.getByTestId('timeline-feed'))
    .toHaveAttribute('data-loaded', 'true', { timeout: 60_000 })
}

test('CMP-100: 組織DESCENDANTS投稿の個人feed表示・詳細・再読込・非対象拒否', async ({ browser }) => {
  test.setTimeout(externalRemoval ? 900_000 : 600_000)
  const admin: BrowserContext = await browser.newContext()
  const member: BrowserContext = await browser.newContext()
  const outsider: BrowserContext = await browser.newContext()
  let postId: number | undefined
  let scenarioCompleted = false
  let cleanupFailure: unknown
  const marker = `CMP100-${Date.now()}`
  const baseUrl = required('API_BASE_URL', apiBaseUrl).replace(/\/$/, '')
  const organizationSlug = required('E2E_PARENT_ORG_SLUG', parentOrgSlug)

  try {
    const adminPage = await admin.newPage()
    await loginViaApi(adminPage, {
      email: required('TEST_ADMIN_EMAIL', adminEmail),
      password: required('TEST_ADMIN_PASSWORD', adminPassword),
    })
    const [organizationResponse, permissionResponse] = await Promise.all([
      admin.request.get(`${baseUrl}/api/v1/organizations/${organizationSlug}`),
      admin.request.get(`${baseUrl}/api/v1/organizations/${organizationSlug}/me/permissions`),
    ])
    if (!organizationResponse.ok()) {
      throw new Error(`organization fixture unavailable: HTTP ${organizationResponse.status()}`)
    }
    if (!permissionResponse.ok()) {
      throw new Error(`admin permission fixture unavailable: HTTP ${permissionResponse.status()}`)
    }
    await adminPage.goto(`/organizations/${organizationSlug}/timeline`)
    await waitForHydration(adminPage)
    await expect(adminPage.getByTestId('team-timeline-composer')).toBeVisible({ timeout: 90_000 })
    await adminPage.getByTestId('timeline-delivery-scope-DESCENDANTS').click()
    await adminPage.getByTestId('team-timeline-composer').fill(marker)
    const created = adminPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/posts')
        && response.request().method() === 'POST',
    )
    await adminPage.getByTestId('team-timeline-submit').click()
    const createdResponse = await created
    expect(createdResponse.status()).toBe(201)
    const body = await createdResponse.json() as { data?: { id?: number }, id?: number }
    postId = body.data?.id ?? body.id
    if (postId === undefined) throw new Error('投稿IDが取得できませんでした。')

    const adminFeed = await openPersonalFeed(adminPage, marker)
    const adminPermalink = adminFeed.getByTestId('team-timeline-post').filter({ hasText: marker })
      .getByTestId('timeline-post-permalink')
    await openPostDetail(adminPage, adminPermalink, postId, marker)
    await returnToPersonalFeed(adminPage, adminFeed, marker)

    const memberPage = await member.newPage()
    await loginViaApi(memberPage, {
      email: required('TEST_USER_EMAIL', memberEmail),
      password: required('TEST_USER_PASSWORD', memberPassword),
    })
    const memberFeed = await openPersonalFeed(memberPage, marker)
    const memberPermalink = memberFeed.getByTestId('team-timeline-post').filter({ hasText: marker })
      .getByTestId('timeline-post-permalink')
    await openPostDetail(memberPage, memberPermalink, postId, marker)
    await returnToPersonalFeed(memberPage, memberFeed, marker)
    for (let attempt = 0; attempt < 3; attempt++) {
      await reloadPersonalFeed(memberPage, memberFeed)
      await expect(memberFeed.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
    }

    if (externalRemoval) {
      console.log('CMP100_READY_FOR_MEMBERSHIP_REMOVAL')
      let disappeared = false
      for (let attempt = 0; attempt < 30; attempt++) {
        await memberPage.waitForTimeout(2_000)
        const feedAfterRemoval = await member.request.get(`${baseUrl}/api/v1/timeline/my`)
        expect(feedAfterRemoval.status()).toBe(200)
        if (!JSON.stringify(await feedAfterRemoval.json()).includes(marker)) {
          disappeared = true
          break
        }
      }
      expect(disappeared).toBe(true)
      await reloadPersonalFeed(memberPage, memberFeed)
      await expect(memberFeed.getByText(marker, { exact: true })).toHaveCount(0)
      await assertDetailDenied(memberPage, postId, marker)
    }

    const outsiderPage = await outsider.newPage()
    await loginViaApi(outsiderPage, {
      email: required('TEST_OUTSIDER_EMAIL', outsiderEmail),
      password: required('TEST_OUTSIDER_PASSWORD', outsiderPassword),
    })
    const outsiderFeed = outsiderPage.waitForResponse(
      response => response.url().includes('/api/v1/timeline/my')
        && response.request().method() === 'GET',
    )
    await outsiderPage.goto('/')
    await waitForHydration(outsiderPage)
    await waitForSpinnerGone(outsiderPage)
    expect((await outsiderFeed).status()).toBe(200)
    await expect(outsiderPage.locator('#scope-panel-PERSONAL').getByText(marker, { exact: true }))
      .toHaveCount(0)
    await assertDetailDenied(outsiderPage, postId, marker)
    scenarioCompleted = true
  } finally {
    const cleanupErrors: unknown[] = []
    if (postId) {
      try {
        const cleanup = await admin.request.delete(`${baseUrl}/api/v1/timeline/posts/${postId}`)
        if (![200, 204, 404].includes(cleanup.status())) {
          cleanupErrors.push(new Error(`CMP-100 cleanup failed: HTTP ${cleanup.status()}`))
        }
      } catch (error) {
        cleanupErrors.push(error)
      }
    }
    const closeResults = await Promise.allSettled([
      admin.close(),
      member.close(),
      outsider.close(),
    ])
    cleanupErrors.push(...closeResults
      .filter((result): result is PromiseRejectedResult => result.status === 'rejected')
      .map(result => result.reason))
    if (scenarioCompleted && cleanupErrors.length > 0) {
      cleanupFailure = cleanupErrors[0]
    } else {
      cleanupErrors.forEach(error => console.error('CMP-100 cleanup failed', error))
    }
  }
  if (cleanupFailure) throw cleanupFailure
})
