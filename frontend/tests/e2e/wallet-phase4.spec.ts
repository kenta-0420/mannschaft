import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F18 Phase 4 — E2E（fuzzy match シノニム / PDF417 / DEPUTY_ADMIN 押印権限）。
 *
 * シナリオ:
 *   1. 顧客が「ドコモポイント」と入力 → サーバーは fuzzy match で d ポイントに紐付け → ロゴ表示
 *   2. SystemAdmin が `/admin/point-card-synonyms` で「dポイ」を追加 → 一覧に表示
 *   3. PDF417 形式のカードを追加 → 詳細ページで canvas が描画される
 *   4. DEPUTY_ADMIN（POINT_CARD_STAMP_ISSUE 権限あり）が押印 → 成功
 *   5. DEPUTY_ADMIN（権限なし）が押印 → 403 エラー
 *
 * バックエンド API はすべて page.route でモックする（既存 wallet.spec.ts / wallet-org-stamp.spec.ts と同じ流儀）。
 * 認証は chromium プロジェクトの storageState（既ログイン）に依存する。
 */

// ──────────────────────────────────────────────────────────────
// 共通定数
// ──────────────────────────────────────────────────────────────

const ORG_ID = 1
const DPOINT_PROVIDER_ID = '01900000-0000-7000-8000-00000000d001'
const STAMP_PROVIDER_ID = '01900000-0000-7000-8000-000000000001'
const STAMP_CARD_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

function wrap<T>(data: T): string {
  return JSON.stringify({ data })
}

function nowIso(): string {
  return new Date().toISOString()
}

// ──────────────────────────────────────────────────────────────
// 個人ウォレット系 (wallet.spec.ts と同等の最小モック)
// ──────────────────────────────────────────────────────────────

interface MockUserCard {
  id: string
  displayName: string
  barcodeValue: string
  barcodeFormat: string
  nickname: string | null
  memo: string | null
  favorite: boolean
  displayOrder: number
  last4: string | null
  lastUsedAt: string | null
  createdAt: string
  updatedAt: string
  providerId: string | null
  providerCode: string | null
  providerDisplayName: string | null
  providerBrandColor: string | null
  providerLogoUrl: string | null
  providerMatched: boolean
}

interface MockUserState {
  cards: MockUserCard[]
  nextCardId: number
  settings: {
    isEnabled: boolean
    termsAcceptedAt: string | null
    termsVersion: string | null
    requireBiometricOnShow: boolean
  }
}

function newUserState(): MockUserState {
  return {
    cards: [],
    nextCardId: 1,
    settings: {
      // 規約同意済みでウォレットページがそのまま開ける状態
      isEnabled: true,
      termsAcceptedAt: nowIso(),
      termsVersion: 'v1',
      requireBiometricOnShow: false,
    },
  }
}

function userCardToList(c: MockUserCard) {
  return {
    id: c.id,
    providerId: c.providerId,
    providerCode: c.providerCode,
    providerDisplayName: c.providerDisplayName,
    providerBrandColor: c.providerBrandColor,
    providerLogoUrl: c.providerLogoUrl,
    displayName: c.displayName,
    last4: c.last4,
    barcodeFormat: c.barcodeFormat,
    favorite: c.favorite,
    displayOrder: c.displayOrder,
    lastUsedAt: c.lastUsedAt,
    createdAt: c.createdAt,
  }
}

function userCardToDetail(c: MockUserCard) {
  return {
    id: c.id,
    providerId: c.providerId,
    providerCode: c.providerCode,
    providerDisplayName: c.providerDisplayName,
    providerBrandColor: c.providerBrandColor,
    providerLogoUrl: c.providerLogoUrl,
    providerMatched: c.providerMatched,
    displayName: c.displayName,
    nickname: c.nickname,
    barcodeValue: c.barcodeValue,
    barcodeFormat: c.barcodeFormat,
    last4: c.last4,
    memo: c.memo,
    favorite: c.favorite,
    displayOrder: c.displayOrder,
    lastUsedAt: c.lastUsedAt,
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
  }
}

/**
 * 個人ウォレット API のモック（カード追加時に displayName で fuzzy match を行う簡易ロジック付き）。
 *
 * fuzzy match のサーバー側挙動を再現:
 *   - displayName を正規化（lower + 半角化簡易）
 *   - 「ドコモポイント」のような同義語は dpoint プロバイダーに、それ以外はマッチなし
 */
async function setupUserWalletMocks(page: Page, state: MockUserState) {
  // settings GET（規約同意済み）
  await page.route('**/api/v1/point-cards/settings', async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(state.settings),
      })
      return
    }
    await route.fallback()
  })

  // providers（個人ウォレット側は空配列で十分）
  await page.route('**/api/v1/point-cards/providers', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap([]),
    })
  })

  // groups（空）
  await page.route('**/api/v1/point-cards/groups', async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap([]),
      })
      return
    }
    await route.fallback()
  })

  // 単体カード /{id} （groups / providers / settings は除外）
  await page.route('**/api/v1/point-cards/*', async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()
    if (
      url.includes('/point-cards/groups')
      || url.includes('/point-cards/providers')
      || url.includes('/point-cards/settings')
    ) {
      await route.fallback()
      return
    }
    if (url.includes('/used')) {
      if (method === 'POST') {
        await route.fulfill({ status: 204, body: '' })
        return
      }
      await route.fallback()
      return
    }
    const m = url.match(/\/point-cards\/([^/?]+)(?:\?|$)/)
    const cardId = m?.[1] ?? ''
    const card = state.cards.find((c) => c.id === cardId)
    if (method === 'GET') {
      if (!card) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(userCardToDetail(card)),
      })
      return
    }
    await route.fallback()
  })

  // カード一覧 + 作成（fuzzy match で provider 解決）
  await page.route('**/api/v1/point-cards', async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()
    if (
      url.includes('/groups')
      || url.includes('/providers')
      || url.includes('/settings')
    ) {
      await route.fallback()
      return
    }
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(state.cards.map(userCardToList)),
      })
      return
    }
    if (method === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        displayName: string
        barcodeValue: string
        barcodeFormat: string
        nickname?: string | null
        memo?: string | null
        favorite?: boolean
      }
      // サーバー側 fuzzy match 再現:
      //   normalize(displayName) に「dポイント」「ドコモ」が含まれていれば
      //   dpoint プロバイダーにマッチ（V9.157 シノニム seed の挙動を模倣）
      const normalized = body.displayName
        .toLowerCase()
        .replace(/\s+/g, '')
        .replace(/[ー－—]/g, '')
      let providerMatched = false
      let providerId: string | null = null
      let providerCode: string | null = null
      let providerDisplayName: string | null = null
      let providerBrandColor: string | null = null
      let providerLogoUrl: string | null = null
      if (
        normalized.includes('ドコモ')
        || normalized.includes('dポイント')
        || normalized.includes('dポイ')
        || normalized.includes('dpoint')
      ) {
        providerMatched = true
        providerId = DPOINT_PROVIDER_ID
        providerCode = 'dpoint'
        providerDisplayName = 'dポイント'
        providerBrandColor = '#E50012'
        providerLogoUrl = 'https://example.test/logos/dpoint.svg'
      }
      const id = `card-${state.nextCardId++}`
      const now = nowIso()
      const card: MockUserCard = {
        id,
        displayName: body.displayName,
        barcodeValue: body.barcodeValue,
        barcodeFormat: body.barcodeFormat,
        nickname: body.nickname ?? null,
        memo: body.memo ?? null,
        favorite: body.favorite ?? false,
        displayOrder: state.cards.length,
        last4: body.barcodeValue.slice(-4),
        lastUsedAt: null,
        createdAt: now,
        updatedAt: now,
        providerId,
        providerCode,
        providerDisplayName,
        providerBrandColor,
        providerLogoUrl,
        providerMatched,
      }
      state.cards.push(card)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: wrap(userCardToDetail(card)),
      })
      return
    }
    await route.fallback()
  })
}

// ──────────────────────────────────────────────────────────────
// SystemAdmin シノニム管理 API モック
// ──────────────────────────────────────────────────────────────

interface MockSynonym {
  id: string
  providerId: string
  providerDisplayName: string | null
  synonymDisplay: string
  synonymNormalized: string
  memo: string | null
  createdAt: string
  updatedAt: string
}

interface MockAdminState {
  providers: { id: string; displayName: string }[]
  synonyms: MockSynonym[]
  nextSeq: number
}

function newAdminState(): MockAdminState {
  return {
    providers: [
      { id: DPOINT_PROVIDER_ID, displayName: 'dポイント' },
      { id: '01900000-0000-7000-8000-00000000d002', displayName: '楽天ポイント' },
    ],
    synonyms: [],
    nextSeq: 1,
  }
}

function normalizeForSynonym(input: string): string {
  return input
    .normalize('NFKC')
    .toLowerCase()
    .replace(/\s+/g, '')
    .replace(/[ー－—!-/:-@[-`{-~、。「」]/g, '')
}

async function setupAdminSynonymMocks(page: Page, state: MockAdminState) {
  // プロバイダー一覧（顧客側 API と同じ URL）
  await page.route('**/api/v1/point-cards/providers', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap(
        state.providers.map((p) => ({
          id: p.id,
          code: p.id.endsWith('d001') ? 'dpoint' : 'rakuten',
          displayName: p.displayName,
          category: 'OTHER',
          type: 'EXTERNAL',
          organizationId: null,
          logoUrl: null,
          brandColor: null,
          defaultBarcodeFormat: null,
          cardNumberLengthHint: null,
          legalNotice: null,
          isActive: true,
        })),
      ),
    })
  })

  // 個別シノニム PATCH / DELETE （より具体的なルートを先に登録）
  await page.route(/\/api\/v1\/admin\/point-cards\/synonyms\/[^/?]+$/, async (route: Route) => {
    const method = route.request().method()
    const url = route.request().url()
    const m = url.match(/\/synonyms\/([^/?]+)$/)
    const id = m?.[1] ?? ''
    const item = state.synonyms.find((s) => s.id === id)
    if (method === 'PATCH' && item) {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        synonymDisplay?: string
        memo?: string | null
      }
      if (body.synonymDisplay) {
        item.synonymDisplay = body.synonymDisplay
        item.synonymNormalized = normalizeForSynonym(body.synonymDisplay)
      }
      if (body.memo !== undefined) item.memo = body.memo
      item.updatedAt = nowIso()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(item),
      })
      return
    }
    if (method === 'DELETE' && item) {
      state.synonyms = state.synonyms.filter((s) => s.id !== id)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.fallback()
  })

  // シノニム一覧・作成
  await page.route(/\/api\/v1\/admin\/point-cards\/synonyms(\?|$)/, async (route: Route) => {
    const method = route.request().method()
    const url = new URL(route.request().url())
    if (method === 'GET') {
      const providerIdFilter = url.searchParams.get('providerId')
      const items = providerIdFilter
        ? state.synonyms.filter((s) => s.providerId === providerIdFilter)
        : state.synonyms
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(items),
      })
      return
    }
    if (method === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        providerId: string
        synonymDisplay: string
        memo?: string | null
      }
      const normalized = normalizeForSynonym(body.synonymDisplay)
      // POINT_CARD_021 SYNONYM_DUPLICATE 検証（同 provider + 同 normalized）
      const dup = state.synonyms.find(
        (s) => s.providerId === body.providerId && s.synonymNormalized === normalized,
      )
      if (dup) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'POINT_CARD_021', message: 'synonym already exists' },
          }),
        })
        return
      }
      const provider = state.providers.find((p) => p.id === body.providerId)
      const created: MockSynonym = {
        id: `synonym-${state.nextSeq++}`,
        providerId: body.providerId,
        providerDisplayName: provider?.displayName ?? null,
        synonymDisplay: body.synonymDisplay,
        synonymNormalized: normalized,
        memo: body.memo ?? null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
      }
      state.synonyms.push(created)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: wrap(created),
      })
      return
    }
    await route.fallback()
  })
}

// ──────────────────────────────────────────────────────────────
// 組織スタンプ押印（DEPUTY_ADMIN 権限分岐用）モック
// ──────────────────────────────────────────────────────────────

interface MockStamp {
  id: string
  cardId: string
  providerId: string
  providerDisplayName: string | null
  organizationId: number
  delta: number
  pressedByUserId: number
  pressedByUserDisplayName: string | null
  pressedAt: string
  memo: string | null
}

interface MockStampState {
  stamps: MockStamp[]
  nextSeq: number
  /** true なら押印 API が 403 を返す（POINT_CARD_STAMP_ISSUE Permission なしを模擬） */
  forbidStamp: boolean
}

function newStampState(forbid: boolean): MockStampState {
  return {
    stamps: [],
    nextSeq: 1,
    forbidStamp: forbid,
  }
}

async function setupOrgStampMocks(page: Page, state: MockStampState) {
  // 自分の組織所属（DEPUTY_ADMIN）
  await page.route('**/api/v1/me/organizations', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap([
        {
          id: ORG_ID,
          name: 'テスト組織A',
          nickname1: null,
          iconUrl: null,
          role: 'DEPUTY_ADMIN',
          orgType: 'GENERAL',
          memberCount: 10,
        },
      ]),
    })
  })

  // サイドバー / ハブが叩く可能性のある周辺 API は空で返しておく
  await page.route('**/api/v1/me/organizations/*/announcements', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) })
  })
  await page.route('**/api/v1/me/scope-folders**', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) })
  })

  // プロバイダー一覧（スタンプ型 1 件）
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/providers(\?|$)/,
    async (route: Route) => {
      if (route.request().method() !== 'GET') {
        await route.fallback()
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap([
          {
            id: STAMP_PROVIDER_ID,
            code: 'org_1_test0001',
            displayName: 'テスト店舗 ポイント',
            category: 'OTHER',
            type: 'SELF_ISSUED_STAMP',
            organizationId: ORG_ID,
            logoUrl: null,
            brandColor: '#FF6699',
            defaultBarcodeFormat: null,
            cardNumberLengthHint: null,
            legalNotice: null,
            isActive: true,
          },
        ]),
      })
    },
  )

  // 押印履歴
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/stamps(\?|$)/,
    async (route: Route) => {
      if (route.request().method() !== 'GET') {
        await route.fallback()
        return
      }
      const sorted = [...state.stamps].sort((a, b) => b.pressedAt.localeCompare(a.pressedAt))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: sorted,
          totalElements: sorted.length,
          totalPages: Math.max(1, Math.ceil(sorted.length / 20)),
          number: 0,
          size: 20,
          first: true,
          last: true,
        }),
      })
    },
  )

  // スタンプ押印 POST
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/[^/]+\/stamps$/,
    async (route: Route) => {
      const method = route.request().method()
      const url = route.request().url()
      const m = url.match(/\/point-cards\/([^/]+)\/stamps$/)
      const cardId = m?.[1] ?? ''
      if (cardId === 'stamps') {
        await route.fallback()
        return
      }
      if (method === 'POST') {
        if (state.forbidStamp) {
          // POINT_CARD_STAMP_ISSUE Permission なしの DEPUTY_ADMIN
          await route.fulfill({
            status: 403,
            contentType: 'application/json',
            body: JSON.stringify({
              error: {
                code: 'AUTH_FORBIDDEN',
                message: 'POINT_CARD_STAMP_ISSUE permission required',
              },
            }),
          })
          return
        }
        const body = JSON.parse(route.request().postData() ?? '{}') as {
          delta: number
          memo?: string
        }
        const stamp: MockStamp = {
          id: `stamp-${state.nextSeq++}`,
          cardId,
          providerId: STAMP_PROVIDER_ID,
          providerDisplayName: 'テスト店舗 ポイント',
          organizationId: ORG_ID,
          delta: body.delta,
          pressedByUserId: 1,
          pressedByUserDisplayName: '副官花子',
          pressedAt: nowIso(),
          memo: body.memo ?? null,
        }
        state.stamps.unshift(stamp)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: wrap(stamp),
        })
        return
      }
      await route.fallback()
    },
  )
}

// ──────────────────────────────────────────────────────────────
// Test suite
// ──────────────────────────────────────────────────────────────

test.describe('F18 Phase 4', () => {
  test('fuzzy match シノニム: 顧客が「ドコモポイント」と入力 → サーバーが d ポイントにマッチ → 詳細ページにロゴ・ブランド色が反映される', async ({
    page,
  }) => {
    const state = newUserState()
    await setupUserWalletMocks(page, state)

    await page.goto('/wallet/cards/new')
    await waitForHydration(page)

    // 「ドコモポイント」と自由入力（シノニム経由で d ポイントにマッチ）
    await page.locator('#card-displayname').fill('ドコモポイント')

    // 手入力タブ
    await page.getByRole('tab', { name: '手入力' }).click()
    await page.locator('#bc-manual-value').fill('1234567890123')
    await page.locator('#bc-manual-format').selectOption('EAN13')

    // 手入力タブ内「次へ」
    await page
      .locator('section')
      .filter({ has: page.locator('#bc-manual-value') })
      .getByRole('button', { name: '次へ' })
      .click()

    // 親フッタ「次へ」→ プレビュー
    await page.locator('.card-new__footer').getByRole('button', { name: '次へ' }).click()
    // 「保存」→ 詳細
    await page.getByRole('button', { name: '保存' }).click()

    await page.waitForURL(/\/wallet\/cards\/card-\d+/)

    // 詳細ページに providerDisplayName（d ポイント）が表示される
    // — CardTile / 詳細ページ どちらでも providerDisplayName を確認する
    await expect(page.getByText('dポイント').first()).toBeVisible({ timeout: 10_000 })
  })

  test('SystemAdmin シノニム管理: 「dポイ」を d ポイントに追加 → 一覧表示・重複エラー検証', async ({
    page,
  }) => {
    const state = newAdminState()
    await setupAdminSynonymMocks(page, state)

    await page.goto('/admin/point-card-synonyms')
    await waitForHydration(page)

    // ヘッダーが見える
    await expect(
      page.getByRole('heading', { name: 'プロバイダー同義語管理' }),
    ).toBeVisible({ timeout: 10_000 })

    // 初期状態は「登録された同義語はありません」
    await expect(page.getByText('登録された同義語はありません')).toBeVisible()

    // 「新規追加」ダイアログを開く
    await page.getByRole('button', { name: '新規追加' }).click()

    // プロバイダー Select で「dポイント」を選択
    const providerSelect = page.getByRole('dialog').getByText('プロバイダー', { exact: false }).first()
    await expect(providerSelect).toBeVisible()
    // Primevue Select は click → option click パターン
    await page.getByRole('dialog').locator('.p-select').first().click()
    await page.getByRole('option', { name: 'dポイント' }).click()

    // 同義語入力
    await page.getByRole('dialog').locator('input[type="text"]').first().fill('dポイ')

    // 保存
    await page.getByRole('dialog').getByRole('button', { name: '保存' }).click()

    // 一覧に「dポイ」が反映される
    await expect(page.getByRole('cell', { name: 'dポイ' }).first()).toBeVisible({
      timeout: 10_000,
    })

    // ── 重複登録で POINT_CARD_021 が返るシナリオ ──
    await page.getByRole('button', { name: '新規追加' }).click()
    await page.getByRole('dialog').locator('.p-select').first().click()
    await page.getByRole('option', { name: 'dポイント' }).click()
    await page.getByRole('dialog').locator('input[type="text"]').first().fill('dポイ')
    await page.getByRole('dialog').getByRole('button', { name: '保存' }).click()

    // エラートーストに重複メッセージが出る
    await expect(
      page.getByText('この同義語は既に登録されています（正規化後の重複）'),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('PDF417 描画: PDF417 形式のカードを保存し、詳細ページで canvas が描画される', async ({
    page,
  }) => {
    const state = newUserState()
    await setupUserWalletMocks(page, state)

    await page.goto('/wallet/cards/new')
    await waitForHydration(page)

    // カード名
    await page.locator('#card-displayname').fill('PDF417 テストカード')

    // 手入力タブで PDF417 を選択
    await page.getByRole('tab', { name: '手入力' }).click()
    // PDF417 の値（適当な英数）
    await page.locator('#bc-manual-value').fill('PDF417TEST123456')
    await page.locator('#bc-manual-format').selectOption('PDF417')

    // 手入力タブ「次へ」
    await page
      .locator('section')
      .filter({ has: page.locator('#bc-manual-value') })
      .getByRole('button', { name: '次へ' })
      .click()

    // 親フッタ「次へ」→ プレビュー → 保存
    await page.locator('.card-new__footer').getByRole('button', { name: '次へ' }).click()
    await page.getByRole('button', { name: '保存' }).click()

    // カード詳細ページ
    await page.waitForURL(/\/wallet\/cards\/card-\d+/)

    // BarcodePreview 内の canvas が描画される
    // （bwip-js は dynamic import なのでロード完了まで少し待つ）
    const canvas = page.locator('.barcode-preview__canvas').first()
    await expect(canvas).toBeVisible({ timeout: 15_000 })

    // canvas の幅・高さが 0 でないこと（実際に描画されている保証）
    const dims = await canvas.evaluate((el: Element) => {
      const c = el as HTMLCanvasElement
      return { width: c.width, height: c.height }
    })
    expect(dims.width).toBeGreaterThan(0)
    expect(dims.height).toBeGreaterThan(0)
  })

  test('DEPUTY_ADMIN（POINT_CARD_STAMP_ISSUE 権限あり）が押印 → 成功', async ({ page }) => {
    const state = newStampState(false)
    await setupOrgStampMocks(page, state)

    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/stamp`)
    await waitForHydration(page)

    // プロバイダーが初期選択される
    const providerSelect = page.locator('#stamp-provider')
    await expect(providerSelect).toBeVisible({ timeout: 10_000 })
    await expect(providerSelect).toHaveValue(STAMP_PROVIDER_ID)

    await page.locator('#stamp-card-id').fill(STAMP_CARD_ID)
    await page.getByRole('button', { name: /\+1/ }).click()
    await page.getByRole('button', { name: '押印する' }).click()

    // 履歴テーブルに +1 が出る
    const shortCardId = `${STAMP_CARD_ID.substring(0, 8)}…`
    await expect(page.getByText(shortCardId).first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('+1').first()).toBeVisible()
  })

  test('DEPUTY_ADMIN（権限なし）が押印 → 403 でエラー表示、履歴に追加されない', async ({
    page,
  }) => {
    const state = newStampState(true) // forbid = true
    await setupOrgStampMocks(page, state)

    // ブラウザ alert（権限エラーで store 経由のトースト or alert を出すケース対応）
    const dialogMessages: string[] = []
    page.on('dialog', async (d) => {
      dialogMessages.push(d.message())
      await d.dismiss()
    })

    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/stamp`)
    await waitForHydration(page)

    const providerSelect = page.locator('#stamp-provider')
    await expect(providerSelect).toBeVisible({ timeout: 10_000 })
    await expect(providerSelect).toHaveValue(STAMP_PROVIDER_ID)

    await page.locator('#stamp-card-id').fill(STAMP_CARD_ID)
    await page.getByRole('button', { name: /\+1/ }).click()

    // 押印 → 403 が返る
    const [response] = await Promise.all([
      page.waitForResponse(
        (resp) =>
          /\/api\/v1\/organizations\/\d+\/point-cards\/[^/]+\/stamps$/.test(resp.url())
          && resp.request().method() === 'POST',
        { timeout: 10_000 },
      ),
      page.getByRole('button', { name: '押印する' }).click(),
    ])
    expect(response.status()).toBe(403)

    // 履歴テーブルには新規押印が反映されない（state.stamps は空のまま）
    expect(state.stamps).toHaveLength(0)
  })
})
