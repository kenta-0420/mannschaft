import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARD_ID_MEMO,
  OWNER_USER_ID,
  PERSONAL_BOARD_ID,
  buildBoardDetail,
  buildCard,
  mockBoardDetail,
  mockCatchAllApis,
  setupOwnerAuth,
} from './_helpers'

/**
 * F09.8 コルクボード 件A 追補 — `viewerCanEdit` による編集ボタン可視性 E2E。
 *
 * <p>背景: 件A の本実装は完了 (commit 84f0863f4) しており、
 * バックエンド `CorkboardPermissionService#canEdit` の判定結果が
 * {@code CorkboardDetail.viewerCanEdit} としてフロントへ返る。
 * 本 spec はその値が UI の編集系コントロール表示に正しく連動することを担保する。</p>
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-PERM-001: PERSONAL ボード所有者 (viewerCanEdit=true) → 編集ボタン表示</li>
 *   <li>CORK-PERM-002: ADMIN_ONLY 共有ボード + viewerCanEdit=true → 編集ボタン表示</li>
 *   <li>CORK-PERM-003: ADMIN_ONLY 共有ボード + viewerCanEdit=false → 編集ボタン非表示</li>
 *   <li>CORK-PERM-004: ALL_MEMBERS 共有ボード + viewerCanEdit=true → 編集可</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md（件A: 編集権限 viewerCanEdit）</p>
 */

const SHARED_BOARD_ID = 700
const SHARED_TEAM_ID = 88

test.describe('CORK-PERM: viewerCanEdit による編集系ボタン制御', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  // ---------------------------------------------------------------------
  // CORK-PERM-001
  // ---------------------------------------------------------------------
  test('CORK-PERM-001: PERSONAL ボード所有者 → 編集ボタン群が表示される', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_MEMO, { body: '所有者として閲覧するメモ' })
    const board = buildBoardDetail([card], {
      scopeType: 'PERSONAL',
      ownerId: OWNER_USER_ID,
      viewerCanEdit: true,
    })
    await mockBoardDetail(page, board)

    await page.goto(`/corkboard/${PERSONAL_BOARD_ID}`)
    await waitForHydration(page)

    // ヘッダーの「+ 新規カード」ボタンが表示される
    await expect(
      page.getByTestId('corkboard-card-create-button'),
    ).toBeVisible({ timeout: 10_000 })

    // カードの編集ボタン群（focus でメニュー表示）
    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await expect(
      page.getByTestId(`corkboard-card-edit-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
    await expect(
      page.getByTestId(`corkboard-card-archive-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
    await expect(
      page.getByTestId(`corkboard-card-delete-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-PERM-002
  // ---------------------------------------------------------------------
  test('CORK-PERM-002: ADMIN_ONLY 共有ボード + viewerCanEdit=true → 編集ボタン群が表示される', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'ADMIN だから編集できる',
      corkboardId: SHARED_BOARD_ID,
    })
    const board = buildBoardDetail([card], {
      id: SHARED_BOARD_ID,
      scopeType: 'TEAM',
      scopeId: SHARED_TEAM_ID,
      ownerId: null,
      editPolicy: 'ADMIN_ONLY',
      viewerCanEdit: true,
    })
    await mockBoardDetail(page, board)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)

    await expect(
      page.getByTestId('corkboard-card-create-button'),
    ).toBeVisible({ timeout: 10_000 })

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await expect(
      page.getByTestId(`corkboard-card-edit-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
    await expect(
      page.getByTestId(`corkboard-card-delete-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-PERM-003
  // ---------------------------------------------------------------------
  test('CORK-PERM-003: ADMIN_ONLY 共有ボード + viewerCanEdit=false → 編集ボタン群が非表示', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: '一般メンバーは編集不可',
      corkboardId: SHARED_BOARD_ID,
    })
    const board = buildBoardDetail([card], {
      id: SHARED_BOARD_ID,
      scopeType: 'TEAM',
      scopeId: SHARED_TEAM_ID,
      ownerId: null,
      editPolicy: 'ADMIN_ONLY',
      viewerCanEdit: false,
    })
    await mockBoardDetail(page, board)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)

    // ボード本体は表示される
    await expect(page.getByTestId('corkboard-detail-page')).toBeVisible({
      timeout: 10_000,
    })

    // 「+ 新規カード」ボタンは非表示（DOM 不在）
    await expect(
      page.getByTestId('corkboard-card-create-button'),
    ).toHaveCount(0)

    // カード操作メニューも edit / archive / delete は非表示
    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await expect(
      page.getByTestId(`corkboard-card-edit-button-${CARD_ID_MEMO}`),
    ).toHaveCount(0)
    await expect(
      page.getByTestId(`corkboard-card-archive-button-${CARD_ID_MEMO}`),
    ).toHaveCount(0)
    await expect(
      page.getByTestId(`corkboard-card-delete-button-${CARD_ID_MEMO}`),
    ).toHaveCount(0)

    // 代わりに「ロック」アイコンが出ているはず（DraggableCard の `!canEdit` 表示）
    await expect(
      page.getByTestId(`corkboard-card-lock-icon-${CARD_ID_MEMO}`),
    ).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-PERM-004
  // ---------------------------------------------------------------------
  test('CORK-PERM-004: ALL_MEMBERS 共有ボード + viewerCanEdit=true → 編集可', async ({
    page,
  }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'メンバー全員編集可ポリシー',
      corkboardId: SHARED_BOARD_ID,
    })
    const board = buildBoardDetail([card], {
      id: SHARED_BOARD_ID,
      scopeType: 'TEAM',
      scopeId: SHARED_TEAM_ID,
      ownerId: null,
      editPolicy: 'ALL_MEMBERS',
      viewerCanEdit: true,
    })
    await mockBoardDetail(page, board)

    await page.goto(`/corkboard/${SHARED_BOARD_ID}`)
    await waitForHydration(page)

    await expect(
      page.getByTestId('corkboard-card-create-button'),
    ).toBeVisible({ timeout: 10_000 })

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await expect(
      page.getByTestId(`corkboard-card-edit-button-${CARD_ID_MEMO}`),
    ).toBeVisible()
    await expect(
      page.getByTestId(`corkboard-card-delete-button-${CARD_ID_MEMO}`),
    ).toBeVisible()

    // canEdit=true のときロックアイコンは出ない（ピン止めもしていない）
    await expect(
      page.getByTestId(`corkboard-card-lock-icon-${CARD_ID_MEMO}`),
    ).toHaveCount(0)
  })
})
