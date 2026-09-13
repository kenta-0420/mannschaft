import { test, expect, type BrowserContext, type Page } from '@playwright/test'
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

async function openPersonalFeed(page: Page, marker: string) {
  const feedResponse = page.waitForResponse(
    response => response.url().includes('/api/v1/timeline/my')
      && response.request().method() === 'GET',
  )
  await page.goto('/')
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  const response = await feedResponse
  expect(response.status()).toBe(200)
  await expect(page.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
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

test('CMP-100: 組織DESCENDANTS投稿の個人feed表示・詳細・再読込・非対象拒否', async ({ browser }) => {
  const admin: BrowserContext = await browser.newContext()
  const member: BrowserContext = await browser.newContext()
  const outsider: BrowserContext = await browser.newContext()
  let postId: number | undefined
  const marker = `CMP100-${Date.now()}`
  const baseUrl = required('API_BASE_URL', apiBaseUrl).replace(/\/$/, '')
  const organizationSlug = required('E2E_PARENT_ORG_SLUG', parentOrgSlug)

  try {
    const adminPage = await admin.newPage()
    await loginViaApi(adminPage, {
      email: required('TEST_ADMIN_EMAIL', adminEmail),
      password: required('TEST_ADMIN_PASSWORD', adminPassword),
    })
    await adminPage.goto(`/organizations/${organizationSlug}/timeline`)
    await waitForHydration(adminPage)
    await expect(adminPage.getByTestId('team-timeline-composer')).toBeVisible({ timeout: 30_000 })
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

    await openPersonalFeed(adminPage, marker)
    const adminPermalink = adminPage.getByTestId('team-timeline-post').filter({ hasText: marker })
      .getByTestId('timeline-post-permalink')
    await adminPermalink.click()
    await expect(adminPage).toHaveURL(new RegExp(`/timeline/${postId}$`))
    await expect(adminPage.getByText(marker, { exact: true })).toBeVisible()
    await adminPage.goBack()
    await expect(adminPage.getByText(marker, { exact: true })).toBeVisible({ timeout: 15_000 })

    const memberPage = await member.newPage()
    await loginViaApi(memberPage, {
      email: required('TEST_USER_EMAIL', memberEmail),
      password: required('TEST_USER_PASSWORD', memberPassword),
    })
    await openPersonalFeed(memberPage, marker)
    const memberPermalink = memberPage.getByTestId('team-timeline-post').filter({ hasText: marker })
      .getByTestId('timeline-post-permalink')
    await memberPermalink.click()
    await expect(memberPage).toHaveURL(new RegExp(`/timeline/${postId}$`))
    await expect(memberPage.getByText(marker, { exact: true })).toBeVisible()
    await memberPage.goBack()
    await expect(memberPage.getByText(marker, { exact: true })).toBeVisible({ timeout: 15_000 })
    for (let attempt = 0; attempt < 3; attempt++) {
      await memberPage.reload({ waitUntil: 'domcontentloaded' })
      await waitForHydration(memberPage)
      await waitForSpinnerGone(memberPage)
      await expect(memberPage.getByText(marker, { exact: true })).toBeVisible({ timeout: 30_000 })
    }

    if (externalRemoval) {
      console.log('CMP100_READY_FOR_MEMBERSHIP_REMOVAL')
      let disappeared = false
      for (let attempt = 0; attempt < 30; attempt++) {
        await memberPage.waitForTimeout(2_000)
        await memberPage.reload({ waitUntil: 'domcontentloaded' })
        await waitForHydration(memberPage)
        await waitForSpinnerGone(memberPage)
        if (await memberPage.getByText(marker, { exact: true }).count() === 0) {
          disappeared = true
          break
        }
      }
      expect(disappeared).toBe(true)
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
    await expect(outsiderPage.getByText(marker, { exact: true })).toHaveCount(0)
    await assertDetailDenied(outsiderPage, postId, marker)
  } finally {
    if (postId) {
      const cleanup = await admin.request.delete(`${baseUrl}/api/v1/timeline/posts/${postId}`)
      expect([200, 204, 404]).toContain(cleanup.status())
    }
    await admin.close()
    await member.close()
    await outsider.close()
  }
})
