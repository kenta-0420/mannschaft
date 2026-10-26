import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_ENDPOINTS = [
  {
    id: 1,
    scopeType: 'ORGANIZATION',
    scopeId: ORG_ID,
    name: 'Slack通知エンドポイント',
    url: 'https://hooks.slack.com/services/xxx',
    isActive: true,
    description: 'Slackへのイベント通知',
    timeoutMs: 5000,
    eventTypes: ['organization.member.joined', 'organization.event.created'],
    createdAt: '2026-01-15T00:00:00Z',
  },
  {
    id: 2,
    scopeType: 'ORGANIZATION',
    scopeId: ORG_ID,
    name: '外部CRM連携',
    url: 'https://crm.example.com/webhook',
    isActive: false,
    description: null,
    timeoutMs: null,
    eventTypes: ['organization.payment.received'],
    createdAt: '2026-02-01T00:00:00Z',
  },
]

const MOCK_DELIVERIES = [
  {
    id: 1,
    endpointId: 1,
    eventType: 'organization.member.joined',
    status: 'SUCCESS',
    httpStatusCode: 200,
    deliveredAt: '2026-04-10T10:00:00Z',
    durationMs: 250,
    requestPayload: '{}',
    responseBody: 'OK',
  },
  {
    id: 2,
    endpointId: 1,
    eventType: 'organization.event.created',
    status: 'FAILED',
    httpStatusCode: 500,
    deliveredAt: '2026-04-11T09:00:00Z',
    durationMs: 3000,
    requestPayload: '{}',
    responseBody: 'Internal Server Error',
  },
]

test.describe('WEBHOOK: Webhook/外部連携API', () => {
  test.beforeEach(async ({ page }) => {
    // 組織基本情報のモック
    await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: ORG_ID,
            name: 'テスト組織',
            slug: 'test-org',
            createdAt: '2026-01-01T00:00:00Z',
          },
        }),
      })
    })
    // 組織権限のモック
    await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            roleName: 'ADMIN',
            permissions: ['webhook.manage'],
          },
        }),
      })
    })
    // 組織配下の全APIをモック
    await page.route(`**/api/v1/organizations/${ORG_ID}/**`, async (route) => {
      const url = route.request().url()
      if (url.includes('/me/permissions')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { roleName: 'ADMIN', permissions: ['webhook.manage'] } }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      }
    })
  })

  test('WEBHOOK-001: Webhookページが表示される', async ({ page }) => {
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'Webhook・外部連携' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('WEBHOOK-002: エンドポイント一覧の取得と表示（GET）', async ({ page }) => {
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ENDPOINTS }),
      })
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByText('Slack通知エンドポイント')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('外部CRM連携')).toBeVisible()
  })

  test('WEBHOOK-003: エンドポイントを追加できる（POST）', async ({ page }) => {
    let createCalled = false

    await page.route('**/api/webhooks/endpoints', async (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 3,
              scopeType: 'ORGANIZATION',
              scopeId: ORG_ID,
              name: '新しいエンドポイント',
              url: 'https://new.example.com/webhook',
              isActive: true,
              description: null,
              timeoutMs: null,
              eventTypes: [],
              signingSecret: 'secret-xxx',
              createdAt: '2026-04-12T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_ENDPOINTS }),
        })
      }
    })
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_ENDPOINTS }),
        })
      }
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByText('Slack通知エンドポイント')).toBeVisible({ timeout: 10_000 })

    // エンドポイント追加ボタンをクリック
    await page.getByRole('button', { name: 'エンドポイントを追加' }).click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // フォームに入力
    await page.getByPlaceholder('例: Slack通知').fill('新しいエンドポイント')
    await page.getByPlaceholder('https://').fill('https://new.example.com/webhook')

    // 追加ボタンをクリック
    await page.getByRole('button', { name: '追加' }).click()

    expect(createCalled).toBe(true)
  })

  test('WEBHOOK-004: エンドポイントを編集できる（PUT）', async ({ page }) => {
    let updateCalled = false

    await page.route('**/api/webhooks/endpoints/1', async (route) => {
      if (route.request().method() === 'PUT') {
        updateCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_ENDPOINTS[0], name: '更新済みエンドポイント' },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_ENDPOINTS[0] }),
        })
      }
    })
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ENDPOINTS }),
      })
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByText('Slack通知エンドポイント')).toBeVisible({ timeout: 10_000 })

    // 編集ボタンをクリック
    const editBtn = page.getByRole('button', { name: '編集' }).first()
    await editBtn.click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // 名前を変更して保存
    const nameInput = page.locator('input').filter({ hasText: '' }).first()
    await nameInput.clear()
    await nameInput.fill('更新済みエンドポイント')

    await page.getByRole('button', { name: '保存' }).click()

    expect(updateCalled).toBe(true)
  })

  test('WEBHOOK-005: エンドポイントを削除できる（DELETE）', async ({ page }) => {
    let deleteCalled = false

    await page.route('**/api/webhooks/endpoints/1', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ENDPOINTS }),
      })
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByText('Slack通知エンドポイント')).toBeVisible({ timeout: 10_000 })

    // 削除ボタンをクリック
    const deleteBtn = page.locator('button').filter({ has: page.locator('.pi-trash') }).first()
    await deleteBtn.click()

    expect(deleteCalled).toBe(true)
  })

  test('WEBHOOK-006: 配信ログが表示される（GET /deliveries）', async ({ page }) => {
    await page.route('**/api/webhooks/endpoints**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ENDPOINTS }),
      })
    })
    await page.route(`**/api/webhooks/endpoints/1/deliveries`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_DELIVERIES }),
      })
    })
    await page.route('**/api/webhooks/incoming**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/api-keys**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/organizations/${ORG_ID}/webhooks`)
    await waitForHydration(page)

    await expect(page.getByText('Slack通知エンドポイント')).toBeVisible({ timeout: 10_000 })

    // 配信ログボタンをクリック
    const logsBtn = page.getByRole('button', { name: '配信ログ' }).first()
    await logsBtn.click()

    // 配信ログダイアログが表示される
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('organization.member.joined')).toBeVisible()
    await expect(page.getByText('organization.event.created')).toBeVisible()
  })
})
