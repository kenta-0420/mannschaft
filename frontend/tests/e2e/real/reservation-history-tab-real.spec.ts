import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

/** CMP-261018-1443: 既存fixtureを読むだけの予約履歴・URL選択復帰確認。 */
const apiBase = process.env.API_BASE_URL ?? 'http://localhost:8080'
const teamSlug = process.env.TEST_RESERVATIONS_TEAM_SLUG ?? 'fc-u-18'
const password = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const memberPath = `/teams/${teamSlug}/reservations`
const consolePath = `/teams/${teamSlug}/admin/reservations`

test.use({ storageState: { cookies: [], origins: [] } })
test.setTimeout(120_000)

async function login(page: Page, email: string): Promise<void> {
  await loginViaApi(page, { email, password }, { apiBaseUrl: apiBase })
}

test.describe('MEMBERの真モバイル', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

  test('自分の予約を選択して離脱後に戻る・進む・再読込しても選択が復元される', async ({ page }) => {
    await login(
      page,
      process.env.TEST_RESERVATIONS_MEMBER_EMAIL ?? 'e2e-user@test.mannschaft.local',
    )
    // role確認だけの前提取得。対象の履歴表示・遷移操作は下のUI経路で実施する。
    const permissionResponse = await page.request.get(
      `${apiBase}/api/v1/teams/${teamSlug}/me/permissions`,
    )
    expect(permissionResponse.status()).toBe(200)
    expect((await permissionResponse.json()).data.roleName).toBe('MEMBER')

    let historyRequests = 0
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === '/api/v1/reservations/my') historyRequests++
    })
    await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === '/api/v1/reservations/my' && response.ok(),
      ),
      page.goto(`${memberPath}?source=history-check#reservation-history`),
    ])
    await waitForHydration(page)
    const mineTab = page.getByRole('tab', { name: '自分の予約', exact: true })
    await expect(mineTab).toBeVisible()
    const historyLength = await page.evaluate(() => history.length)
    const requestsBefore = historyRequests
    await mineTab.click()
    await expect(mineTab).toHaveAttribute('aria-selected', 'true')
    await expect(page).toHaveURL(
      `${new URL(page.url()).origin}${memberPath}?source=history-check&tab=list#reservation-history`,
    )
    expect(await page.evaluate(() => history.length)).toBe(historyLength)
    expect(historyRequests).toBe(requestsBefore)

    const firstRow = page.getByTestId('reservation-list').locator('.p-datatable-tbody tr').first()
    await expect(firstRow).toBeVisible()
    const rowText = await firstRow.innerText()
    await page.locator(`a[href="/teams/${teamSlug}/info"]`).first().click()
    await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/info$`))
    await page.goBack()
    await expect(mineTab).toHaveAttribute('aria-selected', 'true')
    await expect(firstRow).toHaveText(rowText)
    await page.goForward()
    await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/info$`))
    await page.goBack()
    await expect(mineTab).toHaveAttribute('aria-selected', 'true')
    await page.reload()
    await expect(mineTab).toHaveAttribute('aria-selected', 'true')
    await page.goto(`${memberPath}?tab=list`)
    await expect(mineTab).toHaveAttribute('aria-selected', 'true')

    await page.goto(`${memberPath}?tab=manage`)
    await expect(page.getByRole('tab', { name: '予約する', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await expect(page.getByRole('tab', { name: /の管理$/ })).toHaveCount(0)
    await page.screenshot({
      path: 'test-results/reservation-history-tab-member-mobile.png',
      fullPage: true,
    })
  })
})

test('ADMIN: 独立管理ページの管理タブ直URLと緊急休業タブの復帰・再読込', async ({ page }) => {
  await login(
    page,
    process.env.TEST_RESERVATIONS_ADMIN_EMAIL ?? 'e2e-dummy-1@test.mannschaft.local',
  )
  await page.goto(`${consolePath}?tab=manage`)
  const panel = page.getByTestId('team-admin-reservations')
  await expect(panel).toBeVisible()
  const tabs = panel.getByRole('tab')
  await expect(tabs.nth(2)).toHaveAttribute('aria-selected', 'true')
  await tabs.nth(3).click()
  await expect(page).toHaveURL(new RegExp(`${consolePath}\\?tab=closure$`))
  await panel.locator(`a[href="/teams/${teamSlug}/admin"]`).click()
  await expect(page).toHaveURL(new RegExp(`/teams/${teamSlug}/admin$`))
  await page.goBack()
  await expect(tabs.nth(3)).toHaveAttribute('aria-selected', 'true')
  await page.reload()
  await expect(tabs.nth(3)).toHaveAttribute('aria-selected', 'true')
})

test('SUPPORTER: 保管中の元情報を持つ既存キャンセル履歴7件をAPIと画面で照合する', async ({
  page,
}) => {
  await login(
    page,
    process.env.TEST_RESERVATIONS_SUPPORTER_EMAIL ?? 'e2e-supporter@test.mannschaft.local',
  )
  // 画面自身の取得結果を観測し、対象操作の代替API呼出や二重取得をしない。
  const [response] = await Promise.all([
    page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/api/v1/reservations/my' && response.ok(),
    ),
    page.goto(`${memberPath}?tab=list`),
  ])
  expect(response.status()).toBe(200)
  type HistoryRow = {
    slot?: {
      slotDate?: string | null
      startTime?: string | null
      endTime?: string | null
      lineName?: string | null
    } | null
    group?: { groupEndTime?: string | null } | null
    status?: { status?: string }
  }
  const history = (await response.json()).data as HistoryRow[]
  const cancelled = history.filter((row) => row.status?.status === 'CANCELLED')
  expect(cancelled.length, '既存の論理削除履歴fixtureが存在し空検証でない').toBe(7)

  await expect(page.getByRole('tab', { name: '自分の予約', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  const rows = page.getByTestId('reservation-list').locator('.p-datatable-tbody tr')
  for (const row of cancelled) {
    const date = row.slot?.slotDate
    const start = row.slot?.startTime
    const end = row.group ? row.group.groupEndTime : row.slot?.endTime
    const line = row.slot?.lineName
    // 失敗ログへ予約IDや対象名を展開せず、復元された4項目の有無とDOM一致だけを報告する。
    expect(Boolean(date && start && end && line), '保管中の日時・対象名が復元される').toBe(true)
    if (!date || !start || !end || !line) throw new Error('保管中の予約元情報が不足しています')
    await expect
      .poll(
        async () =>
          (await rows.allTextContents()).some((text) =>
            [date, start, end, line].every((value) => text.includes(value)),
          ),
        { message: '復元された履歴の日時と対象が画面にも表示される' },
      )
      .toBe(true)
  }
})
