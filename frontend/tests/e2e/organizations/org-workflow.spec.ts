import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

const MOCK_WORKFLOW_REQUESTS = [
  {
    id: 1,
    templateName: '備品購入申請',
    title: 'プロジェクター購入',
    status: 'PENDING',
    applicantName: 'テストユーザー',
    createdAt: '2026-03-01T10:00:00Z',
  },
]

const MOCK_WORKFLOW_TEMPLATES = [
  {
    id: 1,
    name: '備品購入申請',
    description: '備品購入のための申請フォーム',
    fields: [],
    createdAt: '2026-01-01T00:00:00Z',
  },
]

async function mockWorkflowApis(page: import('@playwright/test').Page) {
  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { roleName: 'MEMBER', permissions: ['workflow.create'] } }),
    })
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/workflow-requests**`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_WORKFLOW_REQUESTS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    } else {
      await route.continue()
    }
  })
  await page.route(`**/api/v1/organizations/${ORG_ID}/workflow-templates**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_WORKFLOW_TEMPLATES }),
    })
  })
}

test.describe('ORG-005〜006: ワークフロー申請', () => {
  test('ORG-005: ワークフロー申請一覧ページが表示される', async ({ page }) => {
    await mockWorkflowApis(page)

    await page.goto(`/organizations/${ORG_ID}/workflows`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ワークフロー申請' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '新規申請' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'テンプレート管理' })).toBeVisible()
  })

  test('ORG-006: 新規申請ボタンクリックでダイアログが表示される', async ({ page }) => {
    await mockWorkflowApis(page)

    await page.goto(`/organizations/${ORG_ID}/workflows`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'ワークフロー申請' })).toBeVisible({
      timeout: 10_000,
    })
    await page.getByRole('button', { name: '新規申請' }).click()

    // ダイアログが開く（WorkflowRequestFormコンポーネント）
    await expect(page.locator('[role="dialog"]')).toBeVisible({ timeout: 5_000 })
  })
})
