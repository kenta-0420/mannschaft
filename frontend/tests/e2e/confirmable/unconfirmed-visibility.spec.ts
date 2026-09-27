import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

type Visibility = 'HIDDEN' | 'CREATOR_AND_ADMIN' | 'ALL_MEMBERS'

async function mockPage(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('currentScope', JSON.stringify({ type: 'team', id: 1, name: 'テストチーム' }))
    localStorage.setItem('accessToken', 'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e')
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem('currentUser', JSON.stringify({ id: 1, email: 'e2e-user@example.com', displayName: 'e2e_user' }))
  })
  await mockTeam(page)
  await page.route('**/api/v1/me/teams', async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }))
  await page.route('**/api/v1/teams/*/confirmable-notification-settings', async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { defaultFirstReminderMinutes: 60, defaultSecondReminderMinutes: 120, senderAlertThresholdPercent: 50, defaultUnconfirmedVisibility: 'CREATOR_AND_ADMIN' } }) }))
  await page.route('**/api/v1/teams/*/confirmable-notification-templates', async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }))
  await page.route('**/api/v1/teams/*/confirmable-recipient-groups', async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) }))
  await page.route('**/api/v1/teams/*/confirmable-notifications/recipient-preview', async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { estimatedRecipientCount: 2 } }) }))
  await page.route('**/api/v1/teams/*/confirmable-notifications', async route => {
    if (route.request().method() === 'GET') await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }) })
    else await route.fallback()
  })
}

test.describe('F04.9 未確認者一覧の公開範囲', () => {
  test.beforeEach(async ({ page }) => { await mockPage(page) })

  test('送信では公開範囲と宛先の既定を渡し、受信者一覧はページAPIの権限境界に従う', async ({ page }) => {
    const sent: { unconfirmedVisibility?: Visibility; targets?: unknown[]; recipientUserIds?: number[] } = {}
    await page.route('**/api/v1/teams/*/confirmable-notifications', async route => {
      if (route.request().method() !== 'POST') return route.fallback()
      Object.assign(sent, route.request().postDataJSON())
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ data: { id: 101, deliveryStatus: 'QUEUED', estimatedRecipientCount: 2 } }) })
    })
    await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
    await waitForHydration(page)
    await expect(page.getByText('見込み受信者: 2人')).toBeVisible()
    await page.locator('label:has-text("タイトル") + input').fill('臨時休業のお知らせ')
    await page.getByTestId('sender-visibility-select').click()
    await page.getByRole('option', { name: '全員に公開' }).click()
    await page.getByRole('button', { name: '確認通知を送信' }).last().click()
    await expect.poll(() => sent).toMatchObject({ unconfirmedVisibility: 'ALL_MEMBERS' })
    expect(sent.targets).toBeUndefined()
    expect(sent.recipientUserIds).toBeUndefined()

    const pagePath = '**/api/v1/teams/*/confirmable-notifications/101/recipients/page?*'
    await page.route(pagePath, async route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { items: [{ id: 2, userId: 11, displayName: '受信者', withdrawn: false, isConfirmed: false, confirmedAt: null, confirmedVia: null }], page: 0, size: 50, totalElements: 1, confirmedCount: 0, unconfirmedCount: 1, viewerRole: 'MEMBER' } }) }))
    const member = await page.evaluate(async () => fetch('/api/v1/teams/1/confirmable-notifications/101/recipients/page?page=0&size=50&unconfirmedOnly=true').then(res => res.json()))
    expect(member.data.viewerRole).toBe('MEMBER')
    expect(member.data.items[0].confirmedAt).toBeNull()
    await page.unroute(pagePath)
    await page.route(pagePath, async route => route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ error: { code: 'CONFIRMABLE_UNCONFIRMED_LIST_FORBIDDEN' } }) }))
    const forbidden = await page.evaluate(async () => fetch('/api/v1/teams/1/confirmable-notifications/101/recipients/page?page=0&size=50&unconfirmedOnly=true').then(res => res.status))
    expect(forbidden).toBe(403)
  })

  for (const scenario of [
    { visibility: 'CREATOR_AND_ADMIN' as const, label: '作成者・管理者のみ' },
    { visibility: 'HIDDEN' as const, label: '表示しない' },
  ]) {
    test(`${scenario.visibility} を送信契約へ渡す`, async ({ page }) => {
      let sentVisibility: Visibility | undefined
      await page.route('**/api/v1/teams/*/confirmable-notifications', async (route) => {
        if (route.request().method() !== 'POST') return route.fallback()
        sentVisibility = route.request().postDataJSON().unconfirmedVisibility
        await route.fulfill({
          status: 202,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { id: 103, deliveryStatus: 'QUEUED', estimatedRecipientCount: 2 },
          }),
        })
      })

      await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
      await waitForHydration(page)
      await page.locator('label:has-text("タイトル") + input').fill('確認依頼')
      await page.getByTestId('sender-visibility-select').click()
      await page.getByRole('option', { name: scenario.label }).click()
      await page.getByRole('button', { name: '確認通知を送信' }).last().click()

      await expect.poll(() => sentVisibility).toBe(scenario.visibility)
    })
  }

  test('前回の公開範囲を復元し、送信後も保存する', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/settings/confirmable-notifications`)
    await waitForHydration(page)
    await page.evaluate(() => localStorage.setItem('confirmable.lastUnconfirmedVisibility', 'ALL_MEMBERS'))
    await page.reload()
    await waitForHydration(page)
    await expect(page.getByTestId('sender-visibility-select')).toContainText('全員に公開')
    await page.route('**/api/v1/teams/*/confirmable-notifications', async route => {
      if (route.request().method() !== 'POST') return route.fallback()
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ data: { id: 102, deliveryStatus: 'QUEUED', estimatedRecipientCount: 2 } }) })
    })
    await page.locator('label:has-text("タイトル") + input').fill('確認依頼')
    await page.getByRole('button', { name: '確認通知を送信' }).last().click()
    await expect.poll(() => page.evaluate(() => localStorage.getItem('confirmable.lastUnconfirmedVisibility'))).toBe('ALL_MEMBERS')
  })
})
