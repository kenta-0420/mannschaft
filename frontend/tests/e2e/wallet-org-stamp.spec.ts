import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F18 Phase 2 — 店主スタンプ押印フロー E2E。
 *
 * シナリオ:
 *   1. 組織 ADMIN として `/organizations/{orgId}/admin/point-cards` を開く
 *   2. 「新規発行」リンク → name="テスト店舗 ポイント" / brandColor="#FF6699" を入力 → 保存
 *   3. 詳細ページに遷移したら「顧客 QR を表示」ボタンを押し、モーダル表示を確認
 *   4. モーダルを閉じて、押印画面（stamp）に遷移
 *   5. プロバイダーを選択、カード ID（UUID）を入力、+1 を選んで押印
 *   6. 直近の押印履歴一覧に押印が表示される
 *
 * バックエンド API はすべて page.route でモックする（既存 wallet.spec.ts と同じ流儀）。
 * 認証は chromium プロジェクトの storageState（既ログイン）に依存する。
 */

const ORG_ID = 1
const PROVIDER_ID = '01900000-0000-7000-8000-000000000001'
const CARD_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

interface MockProvider {
  id: string
  code: string
  displayName: string
  category: string
  type: 'SELF_ISSUED_STAMP' | 'EXTERNAL' | 'SELF_ISSUED_BALANCE'
  organizationId: number
  logoUrl: string | null
  brandColor: string | null
  defaultBarcodeFormat: string | null
  cardNumberLengthHint: string | null
  legalNotice: string | null
  isActive: boolean
}

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

interface MockState {
  providers: MockProvider[]
  stamps: MockStamp[]
  nextProviderSeq: number
  nextStampSeq: number
}

function wrap<T>(data: T): string {
  return JSON.stringify({ data })
}

function nowIso(): string {
  return new Date().toISOString()
}

function newState(): MockState {
  return {
    providers: [],
    stamps: [],
    nextProviderSeq: 1,
    nextStampSeq: 1,
  }
}

/**
 * 共通モック設定。組織情報・サイドバー周辺・ポイントカード関連 API を一括で登録する。
 */
async function setupMocks(page: Page, state: MockState) {
  // 組織所属一覧（ADMIN）
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
          role: 'ADMIN',
          orgType: 'GENERAL',
          memberCount: 10,
        },
      ]),
    })
  })

  // 組織ハブ / レイアウトが叩く可能性のある周辺 API（空配列で十分）
  await page.route('**/api/v1/me/organizations/*/announcements', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap([]),
    })
  })
  await page.route('**/api/v1/me/scope-folders**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap([]),
    })
  })

  // 押印履歴（Page 直返 — ApiResponse でラップしない）
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/stamps(\?|$)/,
    async (route: Route) => {
      const method = route.request().method()
      if (method !== 'GET') {
        await route.fallback()
        return
      }
      const url = new URL(route.request().url())
      const size = Number(url.searchParams.get('size') ?? '20')
      const pageNum = Number(url.searchParams.get('page') ?? '0')
      const providerIdFilter = url.searchParams.get('providerId')
      const filtered = providerIdFilter
        ? state.stamps.filter(s => s.providerId === providerIdFilter)
        : state.stamps
      // 新着順
      const sorted = [...filtered].sort((a, b) => b.pressedAt.localeCompare(a.pressedAt))
      const start = pageNum * size
      const content = sorted.slice(start, start + size)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content,
          totalElements: sorted.length,
          totalPages: Math.max(1, Math.ceil(sorted.length / size)),
          number: pageNum,
          size,
          first: pageNum === 0,
          last: start + size >= sorted.length,
        }),
      })
    },
  )

  // スタンプ押印（POST /point-cards/{cardId}/stamps）
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/[^/]+\/stamps$/,
    async (route: Route) => {
      const method = route.request().method()
      const url = route.request().url()
      // /stamps （履歴）は上で処理済みのため、ここに来るのは /{cardId}/stamps の POST/GET
      const m = url.match(/\/point-cards\/([^/]+)\/stamps$/)
      const cardId = m?.[1] ?? ''
      // 履歴 URL（cardId 部分が "stamps" でないことを確認）
      if (cardId === 'stamps') {
        await route.fallback()
        return
      }
      if (method === 'POST') {
        const body = JSON.parse(route.request().postData() ?? '{}') as {
          delta: number
          memo?: string
        }
        const provider = state.providers.find(p => p.type === 'SELF_ISSUED_STAMP' && p.isActive)
        const stamp: MockStamp = {
          id: `stamp-${state.nextStampSeq++}`,
          cardId,
          providerId: provider?.id ?? PROVIDER_ID,
          providerDisplayName: provider?.displayName ?? null,
          organizationId: ORG_ID,
          delta: body.delta,
          pressedByUserId: 1,
          pressedByUserDisplayName: '店主太郎',
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
      if (method === 'GET') {
        // 単一カードの履歴
        const items = state.stamps.filter(s => s.cardId === cardId)
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: wrap(items),
        })
        return
      }
      await route.fallback()
    },
  )

  // 顧客 QR
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/providers\/[^/]+\/customer-qr$/,
    async (route: Route) => {
      if (route.request().method() !== 'GET') {
        await route.fallback()
        return
      }
      const url = route.request().url()
      const m = url.match(/\/providers\/([^/]+)\/customer-qr/)
      const providerId = m?.[1] ?? ''
      const provider = state.providers.find(p => p.id === providerId)
      if (!provider) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap({
          providerId: provider.id,
          displayName: provider.displayName,
          deepLinkUrl: `mannschaft://wallet/add-from-qr?org=${ORG_ID}&provider=${provider.code}`,
          webUrl: `https://example.test/wallet/add-from-qr?org=${ORG_ID}&provider=${provider.code}`,
        }),
      })
    },
  )

  // 個別プロバイダー GET / PATCH / DELETE
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/providers\/[^/?]+$/,
    async (route: Route) => {
      const url = route.request().url()
      const method = route.request().method()
      // customer-qr 系は上で処理済みのため除外
      if (url.includes('/customer-qr')) {
        await route.fallback()
        return
      }
      const m = url.match(/\/providers\/([^/?]+)$/)
      const providerId = m?.[1] ?? ''
      const provider = state.providers.find(p => p.id === providerId)
      if (method === 'GET') {
        if (!provider) {
          await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: wrap(provider),
        })
        return
      }
      if (method === 'PATCH' && provider) {
        const body = JSON.parse(route.request().postData() ?? '{}') as Partial<MockProvider>
        if (body.displayName) provider.displayName = body.displayName
        if (body.brandColor !== undefined) provider.brandColor = body.brandColor ?? null
        if (body.logoUrl !== undefined) provider.logoUrl = body.logoUrl ?? null
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: wrap(provider),
        })
        return
      }
      if (method === 'DELETE' && provider) {
        provider.isActive = false
        await route.fulfill({ status: 204, body: '' })
        return
      }
      await route.fallback()
    },
  )

  // プロバイダー一覧 / 作成
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/providers(\?|$)/,
    async (route: Route) => {
      const method = route.request().method()
      const url = new URL(route.request().url())
      if (method === 'GET') {
        const activeOnly = url.searchParams.get('active') !== 'false'
        const items = activeOnly
          ? state.providers.filter(p => p.isActive)
          : state.providers
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: wrap(items),
        })
        return
      }
      if (method === 'POST') {
        const body = JSON.parse(route.request().postData() ?? '{}') as {
          displayName: string
          brandColor?: string
          logoUrl?: string
        }
        const seq = state.nextProviderSeq++
        const created: MockProvider = {
          id: PROVIDER_ID,
          code: `org_${ORG_ID}_test${seq.toString().padStart(4, '0')}`,
          displayName: body.displayName,
          category: 'OTHER',
          type: 'SELF_ISSUED_STAMP',
          organizationId: ORG_ID,
          logoUrl: body.logoUrl ?? null,
          brandColor: body.brandColor ?? null,
          defaultBarcodeFormat: null,
          cardNumberLengthHint: null,
          legalNotice: null,
          isActive: true,
        }
        state.providers.push(created)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: wrap(created),
        })
        return
      }
      await route.fallback()
    },
  )
}

test.describe('F18 Phase 2 店主スタンプ押印フロー', () => {
  test('ADMIN がプロバイダーを発行 → QR 表示 → 押印 → 履歴反映までの一連の動線', async ({
    page,
  }) => {
    const state = newState()
    await setupMocks(page, state)

    // ─────────────────────────────────────────────
    // 1. ハブ画面に到達
    // ─────────────────────────────────────────────
    await page.goto(`/organizations/${ORG_ID}/admin/point-cards`)
    await waitForHydration(page)

    // タイトルが見える（i18n キー: wallet.admin.page_title）
    await expect(
      page.getByRole('heading', { level: 1 }),
    ).toBeVisible({ timeout: 10_000 })

    // 「新規発行」リンク（プロバイダー一覧セクションの右上）または
    // ハブ上部の「プロバイダー」アクションカードから新規発行画面へ
    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/providers/new`)
    await waitForHydration(page)

    // ─────────────────────────────────────────────
    // 2. フォーム入力
    // ─────────────────────────────────────────────
    await page.locator('#provider-display-name').fill('テスト店舗 ポイント')
    // ブランドカラー（テキスト入力側）
    const brandColorText = page.locator(
      'input[type="text"][placeholder="#3b82f6"]',
    )
    await brandColorText.fill('#FF6699')

    // 保存ボタン押下 → 詳細画面に遷移
    await Promise.all([
      page.waitForURL(
        new RegExp(`/organizations/${ORG_ID}/admin/point-cards/providers/${PROVIDER_ID}$`),
        { timeout: 10_000 },
      ),
      page.locator('form').getByRole('button', { name: /保存/ }).click(),
    ])

    // 詳細画面で表示名が出ている
    await expect(
      page.getByRole('heading', { name: 'テスト店舗 ポイント' }),
    ).toBeVisible({ timeout: 10_000 })

    // ─────────────────────────────────────────────
    // 3. 顧客 QR モーダルを開く
    // ─────────────────────────────────────────────
    await page.getByRole('button', { name: '顧客向けQRを表示' }).click()

    // モーダルが開く（aria-modal の dialog）
    const qrDialog = page.getByRole('dialog')
    await expect(qrDialog).toBeVisible({ timeout: 5_000 })
    // ディープリンクの URL がコード要素として描画される
    await expect(qrDialog.getByText(/mannschaft:\/\//)).toBeVisible()

    // 閉じる
    await qrDialog.getByRole('button', { name: '閉じる' }).click()
    await expect(qrDialog).not.toBeVisible({ timeout: 5_000 })

    // ─────────────────────────────────────────────
    // 4. 押印画面へ
    // ─────────────────────────────────────────────
    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/stamp`)
    await waitForHydration(page)

    // プロバイダー選択が初期値で SELF_ISSUED_STAMP の最初のものになっている
    const providerSelect = page.locator('#stamp-provider')
    await expect(providerSelect).toBeVisible()
    await expect(providerSelect).toHaveValue(PROVIDER_ID, { timeout: 5_000 })

    // カード ID 入力
    await page.locator('#stamp-card-id').fill(CARD_ID)

    // delta = +1 のクイックボタンが既に選択済み（デフォルト）— 念のため押下
    await page.getByRole('button', { name: /\+1/ }).click()

    // 押印ボタンを押す（POINT_CARD_009 が必要なくて済むよう、Phase 2 ではサーバー側で
    // require_biometric_on_show が無効の状態を想定）
    await page.getByRole('button', { name: '押印する' }).click()

    // ─────────────────────────────────────────────
    // 5. 履歴が反映される（直近 3 件セクション）
    // ─────────────────────────────────────────────
    // 押印された delta=+1 のスタンプが履歴テーブルに出る
    // StampHistoryTable は cardId を 8 文字+"…" で短縮表示する
    const shortCardId = `${CARD_ID.substring(0, 8)}…`
    await expect(page.getByText(shortCardId).first()).toBeVisible({ timeout: 10_000 })
    // delta=+1 が表に表示される
    await expect(page.getByText('+1').first()).toBeVisible()
  })
})
