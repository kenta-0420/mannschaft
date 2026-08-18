import { test, expect } from '@playwright/test'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/** 実BE・実DBを使うため、page.route等のAPIモックは使わない。chromium-realのstorageStateを利用する。 */
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

function japanDate(offsetDays: number): string {
  const date = new Date(Date.now() + offsetDays * 24 * 60 * 60 * 1000)
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
  return `${values.year}-${values.month}-${values.day}`
}

test('F02.11: UIで予定を作成・更新・詳細確認・削除できる', async ({ page }) => {
  const startDate = japanDate(3)
  const endDate = japanDate(10)
  const updatedEndDate = japanDate(12)
  let planId: string | undefined

  try {
    const teamsResponse = await page.request.get('/api/v1/me/teams')
    expect(teamsResponse.status(), '/me/teamsは200').toBe(200)
    const teamsBody = await teamsResponse.json() as { data?: Array<{ id: number; slug: string }> }
    const team = teamsBody.data?.[0]
    expect(team, 'seed済みの所属チームが必要').toBeTruthy()

    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await waitForSpinnerGone(page)
    const addButton = page.getByRole('button', { name: /予定を追加|Add a plan/ }).first()
    await expect(addButton).toBeVisible({ timeout: 30_000 })
    await addButton.click()
    await page.locator('#return-stay-prefecture').selectOption('13')
    await page.locator('#return-stay-start').fill(startDate)
    await page.locator('#return-stay-end').fill(endDate)
    await page.locator('select[aria-label*="チーム"], select[aria-label*="team" i]').selectOption(String(team!.id))

    const createResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'POST' && response.url().includes('/api/v1/me/return-stay-plans'),
    )
    await page.getByRole('button', { name: /保存|Save/ }).click()
    const createResponse = await createResponsePromise
    expect(createResponse.status(), '作成は201').toBe(201)
    const created = await createResponse.json() as { data?: { id?: string } }
    planId = created.data?.id
    expect(planId, '作成応答にplan idが必要').toBeTruthy()

    const planCard = page.locator('li').filter({ hasText: endDate }).first()
    const editButton = planCard.getByRole('button', { name: /編集|Edit/ })
    await expect(editButton).toBeVisible()
    await editButton.click()
    const selectedTeamIds = await page.locator('select[aria-label*="チーム"], select[aria-label*="team" i]').evaluate((select) =>
      Array.from((select as HTMLSelectElement).selectedOptions).map((option) => option.value),
    )
    expect(selectedTeamIds).toContain(String(team!.id))
    await page.locator('#return-stay-end').fill(updatedEndDate)
    const updateResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'PUT' && response.url().includes(`/api/v1/me/return-stay-plans/${planId}`),
    )
    await page.getByRole('button', { name: /保存|Save/ }).click()
    const updateResponse = await updateResponsePromise
    expect(updateResponse.status(), '更新は200').toBe(200)
    const updatePayload = updateResponse.request().postDataJSON() as { teamIds?: number[] }
    expect(updatePayload.teamIds, '更新リクエストでもteamIdsを保持すること').toContain(team!.id)
    await expect(page.getByText(updatedEndDate, { exact: false }).first()).toBeVisible()

    await page.goto(`/teams/${team!.slug}/members`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await waitForSpinnerGone(page)
    const planPill = page.getByRole('button').filter({ hasText: updatedEndDate }).first()
    await expect(planPill).toBeVisible({ timeout: 30_000 })
    await planPill.click()
    await expect(page.locator('.return-stay-detail-dialog')).toBeVisible()
    await expect(page.getByText(updatedEndDate, { exact: false })).toBeVisible()
    await page.getByRole('button', { name: /閉じる|Close/ }).click()

    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await waitForSpinnerGone(page)
    const updatedPlanCard = page.locator('li').filter({ hasText: updatedEndDate }).first()
    await updatedPlanCard.getByRole('button', { name: /削除|Delete/ }).waitFor()
    page.once('dialog', (dialog) => dialog.accept())
    const deleteResponsePromise = page.waitForResponse((response) =>
      response.request().method() === 'DELETE' && response.url().includes(`/api/v1/me/return-stay-plans/${planId}`),
    )
    await updatedPlanCard.getByRole('button', { name: /削除|Delete/ }).click()
    const deleteResponse = await deleteResponsePromise
    expect(deleteResponse.status(), '削除は204').toBe(204)
    planId = undefined
  }
  finally {
    if (planId) {
      const cleanupResponse = await page.request.delete(`/api/v1/me/return-stay-plans/${planId}`)
      expect([204, 404], `cleanupは204または既削除404（実際: ${cleanupResponse.status()}）`).toContain(cleanupResponse.status())
    }
  }
})

test('F02.11: 未認証では保護されたdashboardからloginへ遷移する', async ({ browser }) => {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  try {
    await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
    await page.waitForURL((url) => url.pathname.includes('/login'), { timeout: 30_000 })
    expect(page.url()).toContain('/login')
  }
  finally {
    await context.close()
  }
})
