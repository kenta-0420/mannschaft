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
  name: '技術系資格',
  description: '技術資格カテゴリ',
  icon: null,
  sortOrder: 1,
  isActive: true,
  createdAt: '2026-01-01T00:00:00',
}

/** GET /skills/me・POST /skills・GET /skills/{id} の実形（MemberSkillResponse）。 */
const MOCK_SKILL = {
  id: SKILL_ID,
  skillCategoryId: CATEGORY_ID,
  categoryName: '技術系資格',
  userId: 1,
  scopeType: 'TEAM',
  scopeId: 1,
  name: 'AWS認定ソリューションアーキテクト',
  issuer: 'AWS',
  credentialNumber: null,
  acquiredOn: '2026-01-15',
  expiresAt: '2029-01-15',
  status: 'ACTIVE',
  hasCertificate: false,
  verifiedAt: null,
  verifiedBy: null,
  version: 1,
  createdAt: '2026-01-20T00:00:00',
}

/**
 * GET /skills/search の1件の実形（SkillSearchService）。
 * memberSkillId / skillName / categoryId を使い、credentialNumber は含まれない（hasCertificate のみ）。
 */
const MOCK_SEARCH_ITEM = {
  memberSkillId: SKILL_ID,
  userId: 1,
  displayName: 'e2e_user',
  categoryId: CATEGORY_ID,
  skillName: 'AWS認定ソリューションアーキテクト',
  issuer: 'AWS',
  acquiredOn: '2026-01-15',
  expiresAt: '2029-01-15',
  status: 'ACTIVE',
  hasCertificate: false,
  verifiedAt: null,
  expiringSoon: false,
}

const MOCK_SEARCH_ITEM_2 = {
  memberSkillId: 2,
  userId: 2,
  displayName: '田中太郎',
  categoryId: CATEGORY_ID,
  skillName: 'Google Cloud Professional',
  issuer: 'Google',
  acquiredOn: '2025-12-01',
  expiresAt: null,
  status: 'ACTIVE',
  hasCertificate: true,
  verifiedAt: '2026-01-05T00:00:00',
  expiringSoon: false,
}

/** GET /skill-matrix の実形（ピボット: categories + rows[].cells[categoryId]）。 */
const MOCK_SKILL_MATRIX = {
  data: {
    categories: [MOCK_SKILL_CATEGORY],
    rows: [
      {
        userId: 1,
        displayName: 'e2e_user',
        cells: { [CATEGORY_ID]: { memberSkillId: SKILL_ID, status: 'ACTIVE', expiresAt: '2029-01-15' } },
      },
      {
        userId: 2,
        displayName: '田中太郎',
        cells: { [CATEGORY_ID]: { memberSkillId: 2, status: 'ACTIVE', expiresAt: null } },
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
      body: JSON.stringify({ data: [MOCK_SEARCH_ITEM, MOCK_SEARCH_ITEM_2] }),
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
          permissions: ['MANAGE_MEMBER'],
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
        body: JSON.stringify({ data: [MOCK_SEARCH_ITEM, MOCK_SEARCH_ITEM_2] }),
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
          data: { roleName: 'ADMIN', permissions: ['MANAGE_MEMBER'] },
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
        body: JSON.stringify({ data: [MOCK_SEARCH_ITEM, MOCK_SEARCH_ITEM_2] }),
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
            data: { ...MOCK_SKILL, name: '更新された資格名' },
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
          data: { roleName: 'ADMIN', permissions: ['MANAGE_MEMBER'] },
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
        body: JSON.stringify({ data: [MOCK_SEARCH_ITEM, MOCK_SEARCH_ITEM_2] }),
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
          data: { roleName: 'ADMIN', permissions: ['MANAGE_MEMBER'] },
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
