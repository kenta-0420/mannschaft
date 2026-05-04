import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARD_ID_MEMO,
  PERSONAL_BOARD_ID,
  buildBoardDetail,
  buildCard,
  mockBoardDetail,
  mockCatchAllApis,
  setupOwnerAuth,
  type E2eBoardDetail,
} from './_helpers'

/**
 * F09.8 コルクボード Phase E — セクション CRUD + カード紐付け E2E テスト。
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-SECTION-001: セクション作成 → POST /groups</li>
 *   <li>CORK-SECTION-002: セクション編集 → PUT /groups/{id}</li>
 *   <li>CORK-SECTION-003: セクション削除（確認ダイアログ + メッセージ） → DELETE /groups/{id}</li>
 *   <li>CORK-SECTION-004: カードをセクションに追加 → POST /groups/{id}/cards/{cardId}</li>
 *   <li>CORK-SECTION-005: カードをセクションから外す → DELETE /groups/{id}/cards/{cardId}</li>
 *   <li>CORK-SECTION-006: セクション作成バリデーション（名前未入力エラー）</li>
 *   <li>CORK-SECTION-007: 編集権限なしの場合「+ 新規セクション」ボタン非表示</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md（Phase E セクション機能）</p>
 */

const DETAIL_URL = `/corkboard/${PERSONAL_BOARD_ID}`

// ---------------------------------------------------------------------------
// セクション API モック雛形
// ---------------------------------------------------------------------------

interface SectionApiTracker {
  createPostBody: unknown | null
  updatePutBody: unknown | null
  deleted: boolean
  cardAddedTo: number | null
  cardRemovedFrom: number | null
}

interface SectionMockOptions {
  /** create POST 時に返すセクション (id 付き) */
  createdSection?: {
    id: number
    corkboardId: number
    name: string
    isCollapsed: boolean
    positionX: number
    positionY: number
    width: number
    height: number
    displayOrder: number
    createdAt: string
    updatedAt: string
  }
  /** update PUT 時に返す更新後セクション */
  updatedSection?: SectionMockOptions['createdSection']
  /** 既存セクション ID（DELETE / cards 紐付け対象） */
  existingSectionId?: number
}

async function mockSectionApis(
  page: Page,
  boardId: number,
  options: SectionMockOptions = {},
): Promise<SectionApiTracker> {
  const tracker: SectionApiTracker = {
    createPostBody: null,
    updatePutBody: null,
    deleted: false,
    cardAddedTo: null,
    cardRemovedFrom: null,
  }

  const created = options.createdSection ?? {
    id: 501,
    corkboardId: boardId,
    name: '新規セクション',
    isCollapsed: false,
    positionX: 0,
    positionY: 0,
    width: 400,
    height: 300,
    displayOrder: 0,
    createdAt: '2026-05-03T00:00:00Z',
    updatedAt: '2026-05-03T00:00:00Z',
  }

  // POST /groups
  await page.route(
    `**/api/v1/corkboards/${boardId}/groups`,
    async (route: Route) => {
      if (route.request().method() === 'POST') {
        tracker.createPostBody = route.request().postDataJSON()
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: created }),
        })
      } else {
        await route.continue()
      }
    },
  )

  // PUT / DELETE /groups/{groupId}
  await page.route(
    new RegExp(`/api/v1/corkboards/${boardId}/groups/(\\d+)$`),
    async (route: Route) => {
      const method = route.request().method()
      if (method === 'PUT') {
        tracker.updatePutBody = route.request().postDataJSON()
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: options.updatedSection ?? {
              ...created,
              name: '編集後セクション',
            },
          }),
        })
      } else if (method === 'DELETE') {
        tracker.deleted = true
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    },
  )

  // POST / DELETE /groups/{groupId}/cards/{cardId}
  await page.route(
    new RegExp(`/api/v1/corkboards/${boardId}/groups/(\\d+)/cards/(\\d+)$`),
    async (route: Route) => {
      const method = route.request().method()
      const m = route.request().url().match(/groups\/(\d+)\/cards\/(\d+)$/)
      const groupId = m ? Number(m[1]) : null
      if (method === 'POST') {
        tracker.cardAddedTo = groupId
        await route.fulfill({ status: 201, contentType: 'application/json', body: '{}' })
      } else if (method === 'DELETE') {
        tracker.cardRemovedFrom = groupId
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    },
  )

  return tracker
}

// ---------------------------------------------------------------------------
// 既存セクション 1 件持ちのボード fixture
// ---------------------------------------------------------------------------

const EXISTING_SECTION_ID = 401

function buildBoardWithSection(overrides: Partial<E2eBoardDetail> = {}): E2eBoardDetail {
  return buildBoardDetail([buildCard(CARD_ID_MEMO, { body: 'テスト用 MEMO' })], {
    groups: [
      {
        id: EXISTING_SECTION_ID,
        corkboardId: PERSONAL_BOARD_ID,
        name: '既存セクション',
        isCollapsed: false,
        positionX: 20,
        positionY: 200,
        width: 400,
        height: 300,
        displayOrder: 0,
        createdAt: '2026-04-01T00:00:00Z',
        updatedAt: '2026-04-01T00:00:00Z',
      },
    ],
    ...overrides,
  })
}

// ---------------------------------------------------------------------------
// テスト本体
// ---------------------------------------------------------------------------

test.describe('CORK-SECTION: コルクボードセクション CRUD UI', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-001: セクション作成
  // ---------------------------------------------------------------------
  test('CORK-SECTION-001: セクションを作成できる', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId('corkboard-section-create-button')).toBeVisible()
    await page.getByTestId('corkboard-section-create-button').click()
    await expect(page.getByTestId('section-editor-modal')).toBeVisible()

    await page.getByTestId('section-editor-name-input').fill('新規セクション')
    await page.getByTestId('section-editor-save-button').click()

    await expect(page.getByTestId('section-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.createPostBody).toBeTruthy()
    const body = tracker.createPostBody as Record<string, unknown>
    expect(body.name).toBe('新規セクション')
    expect(body.isCollapsed).toBe(false)
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-002: セクション編集
  // ---------------------------------------------------------------------
  test('CORK-SECTION-002: セクションを編集できる', async ({ page }) => {
    const board = buildBoardWithSection()
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    // セクションホバーで編集ボタンを表示
    const section = page.getByTestId(`corkboard-section-${EXISTING_SECTION_ID}`)
    await expect(section).toBeVisible()
    await section.hover()

    const editButton = page.getByTestId(
      `corkboard-section-edit-button-${EXISTING_SECTION_ID}`,
    )
    await editButton.click({ force: true })

    await expect(page.getByTestId('section-editor-modal')).toBeVisible()
    // edit モードでは既存名が初期表示される
    await expect(page.getByTestId('section-editor-name-input')).toHaveValue('既存セクション')

    await page.getByTestId('section-editor-name-input').fill('編集後セクション')
    await page.getByTestId('section-editor-save-button').click()

    await expect(page.getByTestId('section-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.updatePutBody).toBeTruthy()
    const body = tracker.updatePutBody as Record<string, unknown>
    expect(body.name).toBe('編集後セクション')
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-003: セクション削除（確認ダイアログ）
  // ---------------------------------------------------------------------
  test('CORK-SECTION-003: セクションを削除できる（カードは残る旨のメッセージを表示）', async ({ page }) => {
    const board = buildBoardWithSection()
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const section = page.getByTestId(`corkboard-section-${EXISTING_SECTION_ID}`)
    await section.hover()
    await page
      .getByTestId(`corkboard-section-delete-button-${EXISTING_SECTION_ID}`)
      .click({ force: true })

    // 確認ダイアログ表示 + 「カードは残ります」メッセージを含むこと
    await expect(page.getByText(/セクションを削除しますか/)).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText(/カードは残り/)).toBeVisible()

    // 「はい」相当（PrimeVue ConfirmDialog のデフォルトラベル "はい"）
    await page.getByRole('button', { name: /はい|Yes/ }).click()

    await expect.poll(() => tracker.deleted, { timeout: 5_000 }).toBe(true)
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-004: カードをセクションに追加
  // ---------------------------------------------------------------------
  test('CORK-SECTION-004: カードをセクションに追加できる', async ({ page }) => {
    const board = buildBoardWithSection()
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const card = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await card.hover()

    const sectionButton = page.getByTestId(`corkboard-card-section-button-${CARD_ID_MEMO}`)
    await expect(sectionButton).toBeVisible()
    await sectionButton.click({ force: true })

    // Popover 内の既存セクション項目をクリック
    await page
      .getByTestId(`corkboard-card-section-menu-item-${EXISTING_SECTION_ID}`)
      .click()

    await expect.poll(() => tracker.cardAddedTo, { timeout: 5_000 }).toBe(
      EXISTING_SECTION_ID,
    )
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-005: カードをセクションから外す
  // ---------------------------------------------------------------------
  test('CORK-SECTION-005: カードをセクションから外せる', async ({ page }) => {
    const board = buildBoardWithSection()
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    // 先に追加して所属させる（Popover → 既存セクション）
    const card = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await card.hover()
    await page
      .getByTestId(`corkboard-card-section-button-${CARD_ID_MEMO}`)
      .click({ force: true })
    await page
      .getByTestId(`corkboard-card-section-menu-item-${EXISTING_SECTION_ID}`)
      .click()
    await expect.poll(() => tracker.cardAddedTo).toBe(EXISTING_SECTION_ID)

    // 再度開いて「セクションから外す」をクリック
    await card.hover()
    await page
      .getByTestId(`corkboard-card-section-button-${CARD_ID_MEMO}`)
      .click({ force: true })
    await page.getByTestId('corkboard-card-section-menu-clear').click()

    await expect.poll(() => tracker.cardRemovedFrom, { timeout: 5_000 }).toBe(
      EXISTING_SECTION_ID,
    )
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-006: バリデーション（名前未入力）
  // ---------------------------------------------------------------------
  test('CORK-SECTION-006: 名前未入力ではセクションを作成できない', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockSectionApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('corkboard-section-create-button').click()
    await expect(page.getByTestId('section-editor-modal')).toBeVisible()

    // 名前を空のまま保存
    await page.getByTestId('section-editor-save-button').click()

    // モーダルは閉じない + エラー表示
    await expect(page.getByTestId('section-editor-modal')).toBeVisible()
    await expect(page.getByTestId('section-editor-name-error')).toBeVisible()

    // POST API が呼ばれていないこと
    expect(tracker.createPostBody).toBeNull()
  })

  // ---------------------------------------------------------------------
  // CORK-SECTION-007: 編集権限なしの場合の非表示
  // ---------------------------------------------------------------------
  test('CORK-SECTION-007: 個人ボード非所有者は新規セクションボタンが非表示', async ({ page }) => {
    // ownerId を別 user (=999) にして、currentUser (=1) との不一致状態を作る
    const board = buildBoardDetail([], { ownerId: 999 })
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId('corkboard-detail-page')).toBeVisible()
    await expect(page.getByTestId('corkboard-section-create-button')).toHaveCount(0)
  })
})
