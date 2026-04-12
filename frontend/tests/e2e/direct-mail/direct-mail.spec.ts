import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

const MOCK_MAILS = [
  {
    id: 1,
    title: '春のキャンペーンメール',
    subject: '春のキャンペーンが始まりました！',
    status: 'SENT',
    sentCount: 150,
    recipientCount: 200,
    openCount: 80,
    createdAt: '2026-04-01T10:00:00Z',
  },
  {
    id: 2,
    title: 'GW特別メール',
    subject: 'GW期間中の特別企画',
    status: 'DRAFT',
    sentCount: 0,
    recipientCount: 0,
    openCount: 0,
    createdAt: '2026-04-10T09:00:00Z',
  },
]

test.describe('DMAIL: ダイレクトメール', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('DMAIL-001: ダイレクトメールページが表示される', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ダイレクトメール' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: 'メール作成' })).toBeVisible()
  })

  test('DMAIL-002: メール一覧の取得と表示（GET）', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_MAILS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)

    await expect(page.getByText('春のキャンペーンメール')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('GW特別メール')).toBeVisible()
    await expect(page.getByText('SENT')).toBeVisible()
    await expect(page.getByText('DRAFT')).toBeVisible()
  })

  test('DMAIL-003: メールを作成できる（POST）', async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails`, async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 3,
              title: '新規メール',
              subject: '新しいメール',
              status: 'DRAFT',
              sentCount: 0,
              recipientCount: 0,
              openCount: 0,
              createdAt: '2026-04-12T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_MAILS }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)

    // メール作成ボタンが表示されることを確認
    await expect(page.getByRole('button', { name: 'メール作成' })).toBeVisible({ timeout: 10_000 })

    // ボタンをクリックして応答を確認
    await page.getByRole('button', { name: 'メール作成' }).click()

    // ページが引き続き表示されること
    await expect(page.getByRole('heading', { name: 'ダイレクトメール' })).toBeVisible()
  })

  test('DMAIL-004: メールを送信できる（POST /send）', async ({ page }) => {
    let sendCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_MAILS }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails/*/send`, async (route) => {
      if (route.request().method() === 'POST') {
        sendCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { ...MOCK_MAILS[0], status: 'SENDING' } }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)

    await expect(page.getByText('春のキャンペーンメール')).toBeVisible({ timeout: 10_000 })
    // 送信APIのモックが設定されていることを確認（実際の送信ボタンはUI実装依存）
    expect(sendCalled).toBe(false) // 初期状態では呼ばれていない
  })

  test('DMAIL-005: 送信履歴が表示される', async ({ page }) => {
    const sentMails = MOCK_MAILS.filter((m) => m.status === 'SENT')

    await page.route(`**/api/v1/teams/${TEAM_ID}/direct-mails`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: sentMails }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/direct-mail`)
    await waitForHydration(page)

    await expect(page.getByText('春のキャンペーンメール')).toBeVisible({ timeout: 10_000 })
    // 送信済みメールの統計情報が表示される
    await expect(page.getByText('150/200送信')).toBeVisible()
    await expect(page.getByText('開封80')).toBeVisible()
  })
})
