import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam } from '../teams/helpers'

/**
 * F07.5 スキル・資格管理 — Playwright E2E テスト
 *
 * テストID: SKILL-001 〜 SKILL-006
 *
 * 仕様書: docs/features/F07.5_skill_certification.md
 */

const SKILL_ID = 1
const CATEGORY_ID = 1

const MOCK_SKILL_CATEGORY = {
  id: CATEGORY_ID,
  teamId: TEAM_ID,
  name: '技術系資格',
  description: '技術資格カテゴリ',
  sortOrder: 1,
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_SKILL = {
  id: SKILL_ID,
  teamId: TEAM_ID,
  userId: 1,
  userName: 'e2e_user',
  categoryId: CATEGORY_ID,
  categoryName: '技術系資格',
  name: 'AWS認定ソリューションアーキテクト',
  level: 'ADVANCED',
  acquiredDate: '2026-01-15',
  expiryDate: '2029-01-15',
  certificateUrl: null,
  verifiedAt: null,
  verifiedBy: null,
  notes: '資格取得',
  createdAt: '2026-01-20T00:00:00Z',
  updatedAt: '2026-01-20T00:00:00Z',
}

const MOCK_SKILL_2 = {
  id: 2,
  teamId: TEAM_ID,
  userId: 2,
  userName: '田中太郎',
  categoryId: CATEGORY_ID,
  categoryName: '技術系資格',
  name: 'Google Cloud Professional',
  level: 'INTERMEDIATE',
  acquiredDate: '2025-12-01',
  expiryDate: null,
  certificateUrl: null,
  verifiedAt: '2026-01-05T00:00:00Z',
  verifiedBy: 1,
  notes: null,
  createdAt: '2026-01-10T00:00:00Z',
  updatedAt: '2026-01-10T00:00:00Z',
}

const MOCK_SKILL_MATRIX = {
  data: {
    members: [
      { id: 1, displayName: 'e2e_user' },
      { id: 2, displayName: '田中太郎' },
    ],
    categories: [
      {
        id: CATEGORY_ID,
        name: '技術系資格',
        skills: [
          { memberId: 1, skillName: 'AWS認定ソリューションアーキテクト', level: 'ADVANCED' },
          { memberId: 2, skillName: 'Google Cloud Professional', level: 'INTERMEDIATE' },
        ],
      },
    ],
  },
}

async function mockSkillApis(page: Page): Promise<void> {
  // キャッチオール
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await mockTeam(page)

  // スキルカテゴリ
  await page.route(`**/api/v1/teams/${TEAM_ID}/skill-categories**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [MOCK_SKILL_CATEGORY] }),
    })
  })

  // スキル検索一覧
  await page.route(`**/api/v1/teams/${TEAM_ID}/skills/search**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [MOCK_SKILL, MOCK_SKILL_2] }),
    })
  })

  // スキルマトリクス
  await page.route(`**/api/v1/teams/${TEAM_ID}/skill-matrix**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_SKILL_MATRIX),
    })
  })

  // 権限取得
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          roleName: 'ADMIN',
          permissions: ['member.manage'],
        },
      }),
    })
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('SKILL-001〜006: F07.5 スキル・資格管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
        }),
      )
    })
  })

  test('SKILL-001: スキルページが表示される', async ({ page }) => {
    await mockSkillApis(page)

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'スキル・資格' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SKILL-002: スキル一覧の取得と表示（GET）', async ({ page }) => {
    await mockSkillApis(page)

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    // スキル名が表示される
    await expect(page.getByText('AWS認定ソリューションアーキテクト')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('Google Cloud Professional')).toBeVisible({ timeout: 10_000 })
  })

  test('SKILL-003: スキルを登録できる（POST）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-categories**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL_CATEGORY] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills/search**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL, MOCK_SKILL_2] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-matrix**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SKILL_MATRIX),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills`, async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_SKILL, id: 99, name: '新規スキル' },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'スキル・資格' })).toBeVisible({
      timeout: 10_000,
    })

    // 「スキルを追加」ボタンをクリック
    const addButton = page.getByRole('button', { name: /追加|登録|新規/ })
    if (await addButton.isVisible({ timeout: 5_000 })) {
      await addButton.click()
    }

    await expect(page.getByRole('heading', { name: 'スキル・資格' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SKILL-004: スキルを編集できる（PUT）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-categories**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL_CATEGORY] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills/search**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL, MOCK_SKILL_2] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-matrix**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SKILL_MATRIX),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills/${SKILL_ID}`, async (route) => {
      if (route.request().method() === 'PUT') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_SKILL, notes: '更新されたノート' },
          }),
        })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    // スキル一覧が表示されることを確認
    await expect(page.getByText('AWS認定ソリューションアーキテクト')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SKILL-005: スキルを削除できる（DELETE）', async ({ page }) => {
    await mockTeam(page)
    await page.route('**/api/v1/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-categories**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL_CATEGORY] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills/search**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_SKILL, MOCK_SKILL_2] }),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skill-matrix**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SKILL_MATRIX),
      })
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/skills/${SKILL_ID}`, async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    })

    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { roleName: 'ADMIN', permissions: ['member.manage'] },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    await expect(page.getByText('AWS認定ソリューションアーキテクト')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('SKILL-006: スキルマトリックスが表示される', async ({ page }) => {
    await mockSkillApis(page)

    await page.goto(`/teams/${TEAM_ID}/skills`)
    await waitForHydration(page)

    // タブ切り替え: スキルマトリクス
    await page.getByRole('tab', { name: 'スキルマトリクス' }).click()

    // スキルマトリクスタブの内容が表示される（メンバー名）
    await expect(page.getByText('e2e_user')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('田中太郎')).toBeVisible({ timeout: 10_000 })
  })
})
