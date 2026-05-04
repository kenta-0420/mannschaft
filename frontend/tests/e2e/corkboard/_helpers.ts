import type { Page } from '@playwright/test'

/**
 * F09.8 コルクボード Phase H E2E 共通モック・ヘルパー。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>API モック方式（{@code page.route} で {@code **\/api/v1/...} をモック）</li>
 *   <li>F03.5 シフト管理 ({@link ../shifts/_helpers.ts}) の構成を踏襲</li>
 *   <li>fixture は関数で生成（overrides で一部上書き可能）</li>
 *   <li>バックエンド DTO（{@code CorkboardDetailResponse} / {@code CorkboardCardResponse}）に準拠</li>
 * </ul>
 *
 * <p>認証は各 spec の {@code beforeEach} で {@link setupOwnerAuth} を呼び、
 * localStorage に accessToken / currentUser を注入する方式。</p>
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

/** ボードオーナー（個人ボード所有者）の userId。 */
export const OWNER_USER_ID = 1

/** 個人ボードの boardId。 */
export const PERSONAL_BOARD_ID = 100

/** カード ID 群。 */
export const CARD_ID_MEMO = 201
export const CARD_ID_URL = 202
export const CARD_ID_REFERENCE = 203
export const CARD_ID_REFERENCE_DELETED = 204

// ---------------------------------------------------------------------------
// 認証セットアップ
// ---------------------------------------------------------------------------

/**
 * 個人ボード所有者 (userId = OWNER_USER_ID) としてログイン済み状態をシミュレート。
 * useAuthStore.loadFromStorage で復元される localStorage を注入する。
 */
export async function setupOwnerAuth(page: Page): Promise<void> {
  await page.addInitScript((userId) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: userId,
        email: 'e2e-owner@example.com',
        displayName: 'e2e_owner',
        profileImageUrl: null,
        systemRole: 'USER',
      }),
    )
  }, OWNER_USER_ID)
}

// ---------------------------------------------------------------------------
// 共通 catch-all
// ---------------------------------------------------------------------------

/**
 * すべての {@code /api/v1/**} を空 data で fulfill する catch-all。
 * 各 spec では本関数を最初に呼び、後から個別エンドポイントを上書きモックする
 * （Playwright の page.route は後勝ち）。
 */
export async function mockCatchAllApis(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

// ---------------------------------------------------------------------------
// fixture ビルダ（バックエンド DTO 準拠）
// ---------------------------------------------------------------------------

/**
 * カード DTO 型（{@code CorkboardCardResponse} 準拠）。
 *
 * <p>nullable フィールドは {@code string | null} / {@code number | null} で明示し、
 * spec 側で {@code { ...existing, title: '更新後' }} のように上書きしても
 * リテラル {@code null} に推論されないようにする。</p>
 */
export interface E2eCard {
  id: number
  corkboardId: number
  cardType: 'MEMO' | 'URL' | 'REFERENCE' | 'SECTION_HEADER'
  referenceType: string | null
  referenceId: number | null
  contentSnapshot: string | null
  title: string | null
  body: string | null
  url: string | null
  ogTitle: string | null
  ogImageUrl: string | null
  ogDescription: string | null
  colorLabel: string
  cardSize: string
  positionX: number
  positionY: number
  zIndex: number
  userNote: string | null
  autoArchiveAt: string | null
  isArchived: boolean
  isPinned: boolean
  pinnedAt: string | null
  isRefDeleted: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
  /** 将来的なフィールド追加に備えて拡張可能としておく。 */
  [key: string]: unknown
}

/**
 * 個人ボード詳細 DTO 型（{@code CorkboardDetailResponse} 準拠）。
 */
export interface E2eBoardDetail {
  id: number
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  scopeId: number | null
  ownerId: number | null
  name: string
  backgroundStyle: string
  editPolicy: string
  isDefault: boolean
  version: number
  cards: E2eCard[]
  groups: unknown[]
  createdAt: string
  updatedAt: string
  [key: string]: unknown
}

/**
 * カード DTO の雛形（{@code CorkboardCardResponse} 準拠）。
 *
 * 必要なフィールドのみ overrides で上書きする。
 */
export function buildCard(
  id: number,
  overrides: Partial<E2eCard> = {},
): E2eCard {
  return {
    id,
    corkboardId: PERSONAL_BOARD_ID,
    cardType: 'MEMO',
    referenceType: null,
    referenceId: null,
    contentSnapshot: null,
    title: null,
    body: null,
    url: null,
    ogTitle: null,
    ogImageUrl: null,
    ogDescription: null,
    colorLabel: 'WHITE',
    cardSize: 'MEDIUM',
    positionX: 40,
    positionY: 40,
    zIndex: 1,
    userNote: null,
    autoArchiveAt: null,
    isArchived: false,
    isPinned: false,
    pinnedAt: null,
    isRefDeleted: false,
    createdBy: OWNER_USER_ID,
    createdAt: '2026-05-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

/**
 * 個人ボード詳細 DTO の雛形（{@code CorkboardDetailResponse} 準拠）。
 */
export function buildBoardDetail(
  cards: E2eCard[] = [],
  overrides: Partial<E2eBoardDetail> = {},
): E2eBoardDetail {
  return {
    id: PERSONAL_BOARD_ID,
    scopeType: 'PERSONAL',
    scopeId: null,
    ownerId: OWNER_USER_ID,
    name: 'E2Eテスト用個人ボード',
    backgroundStyle: 'CORK',
    editPolicy: 'ADMIN_ONLY',
    isDefault: false,
    version: 1,
    cards,
    groups: [],
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-05-01T00:00:00Z',
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// API モック
// ---------------------------------------------------------------------------

/**
 * scope-agnostic ボード詳細取得 API をモックする。
 *
 * フロント側 ({@code useCorkboardApi.getBoardDetailByBoardId}) は scope クエリ無しで
 * {@code GET /api/v1/corkboards/{boardId}} を呼ぶため、このパターンをモックする。
 */
export async function mockBoardDetail(
  page: Page,
  board: E2eBoardDetail,
): Promise<void> {
  await page.route(
    `**/api/v1/corkboards/${board.id}`,
    async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: board }),
        })
      } else {
        await route.continue()
      }
    },
  )
}

/**
 * カード CRUD API モックの汎用ハンドラ。
 *
 * 戻り値は呼び出しトラッカ (テスト本体で `expect(tracker.posted).toBe(true)` 等で検証)。
 */
export interface CardApiTracker {
  postedBody: unknown | null
  putBody: unknown | null
  deleted: boolean
  archivedBody: unknown | null
  pinBody: unknown | null
  pinFailStatus: number | null
  pinFailCode: string | null
}

export async function mockCardCrudApis(
  page: Page,
  boardId: number,
  options: {
    onCreatedCard?: E2eCard
    onUpdatedCard?: E2eCard
    onArchivedCard?: E2eCard
    onPinnedCard?: { id: number; isPinned: boolean; pinnedAt: string | null }
    /** 409 + CORKBOARD_013 を返したい場合 true */
    pinShouldFailLimit?: boolean
  } = {},
): Promise<CardApiTracker> {
  const tracker: CardApiTracker = {
    postedBody: null,
    putBody: null,
    deleted: false,
    archivedBody: null,
    pinBody: null,
    pinFailStatus: null,
    pinFailCode: null,
  }

  // POST /cards
  await page.route(
    `**/api/v1/corkboards/${boardId}/cards`,
    async (route) => {
      if (route.request().method() === 'POST') {
        tracker.postedBody = route.request().postDataJSON()
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: options.onCreatedCard ?? buildCard(999, { id: 999 }),
          }),
        })
      } else {
        await route.continue()
      }
    },
  )

  // PUT / DELETE /cards/{cardId}
  await page.route(
    new RegExp(`/api/v1/corkboards/${boardId}/cards/(\\d+)$`),
    async (route) => {
      const method = route.request().method()
      if (method === 'PUT') {
        tracker.putBody = route.request().postDataJSON()
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: options.onUpdatedCard ?? buildCard(0, { id: 0 }),
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

  // PATCH /cards/{cardId}/archive
  await page.route(
    new RegExp(`/api/v1/corkboards/${boardId}/cards/(\\d+)/archive`),
    async (route) => {
      if (route.request().method() === 'PATCH') {
        const url = new URL(route.request().url())
        tracker.archivedBody = {
          archived: url.searchParams.get('archived') ?? 'true',
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data:
              options.onArchivedCard ??
              buildCard(0, { id: 0, isArchived: true }),
          }),
        })
      } else {
        await route.continue()
      }
    },
  )

  // PATCH /cards/{cardId}/pin
  await page.route(
    new RegExp(`/api/v1/corkboards/${boardId}/cards/(\\d+)/pin`),
    async (route) => {
      if (route.request().method() === 'PATCH') {
        tracker.pinBody = route.request().postDataJSON()
        if (options.pinShouldFailLimit) {
          tracker.pinFailStatus = 409
          tracker.pinFailCode = 'CORKBOARD_013'
          await route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'CORKBOARD_013',
              message: 'Pin limit reached',
            }),
          })
          return
        }
        const payload =
          options.onPinnedCard ?? {
            id: 0,
            isPinned: true,
            pinnedAt: '2026-05-03T14:23:00Z',
          }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: payload }),
        })
      } else {
        await route.continue()
      }
    },
  )

  return tracker
}
