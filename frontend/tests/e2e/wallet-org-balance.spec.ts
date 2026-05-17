import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F18 Phase 3 — 店主残高型カード操作フロー E2E。
 *
 * ※ 2026-05-17 マスター御裁可により SELF_ISSUED_BALANCE 機能を凍結（資金決済法対応のため）。
 *   案 B: 機能フラグ（runtimeConfig.public.f18BalanceEnabled=false）+ Service 入口 503 例外
 *   採用。STAMP / EXTERNAL は無傷。設計書 §1.4 / §16 / §17 参照。
 *
 *   本 spec は凍結シナリオを主軸に書き換えた:
 *     - SELF_ISSUED_BALANCE で resolveByToken した場合、操作タブ（チャージ / 利用 / 返金）が
 *       一切表示されないこと
 *     - 「この機能は現在停止中です」バナーと資金決済法対応の理由文が表示されること
 *
 *   元の「ADMIN として CHARGE → SPENT → REFUND 成功」シナリオは下部に skip 付きで残してあり、
 *   v2 で機能再開する際に復活させること。
 */

const ORG_ID = 1
const PROVIDER_ID = '01900000-0000-7000-8000-000000000099'
const CARD_ID = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff'
const TOKEN = 'cccccccc-dddd-eeee-ffff-000000000000'

interface MockBalanceEvent {
  id: string
  cardId: string
  providerId: string
  providerDisplayName: string | null
  organizationId: number
  operationType: 'CHARGE' | 'SPENT' | 'REFUND'
  delta: string
  balanceAfter: string
  refundOfEventId: string | null
  operatedByUserId: number
  operatedByUserDisplayName: string | null
  operatedAt: string
  note: string | null
  createdAt: string
}

interface MockState {
  balance: number
  events: MockBalanceEvent[]
  nextEventSeq: number
}

function wrap<T>(data: T): string {
  return JSON.stringify({ data })
}

function newState(): MockState {
  return {
    balance: 0,
    events: [],
    nextEventSeq: 1,
  }
}

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

  // レイアウト周辺
  await page.route('**/api/v1/me/organizations/*/announcements', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) })
  })
  await page.route('**/api/v1/me/scope-folders**', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) })
  })

  // プロバイダー一覧 — SELF_ISSUED_BALANCE 一件
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
            id: PROVIDER_ID,
            code: 'org_1_balance0001',
            displayName: 'テスト店舗 残高型',
            category: 'OTHER',
            type: 'SELF_ISSUED_BALANCE',
            organizationId: ORG_ID,
            logoUrl: null,
            brandColor: '#3b82f6',
            defaultBarcodeFormat: null,
            cardNumberLengthHint: null,
            legalNotice: null,
            isActive: true,
          },
        ]),
      })
    },
  )

  // resolve-by-token（POST）— TOKEN ヒット時 SELF_ISSUED_BALANCE で応答
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/resolve-by-token$/,
    async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.fallback()
        return
      }
      const body = JSON.parse(route.request().postData() ?? '{}') as { token: string }
      if (body.token !== TOKEN) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ errorCode: 'POINT_CARD_019', message: 'TOKEN_NOT_FOUND' }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap({
          cardId: CARD_ID,
          providerId: PROVIDER_ID,
          providerDisplayName: 'テスト店舗 残高型',
          providerType: 'SELF_ISSUED_BALANCE',
          last4: '1234',
          currentStampCount: null,
          currentBalance: state.balance.toFixed(2),
        }),
      })
    },
  )

  // 残高変動イベント（POST/GET）— 凍結中も GET（履歴）は許可される設計。
  // POST が呼ばれてしまった場合は 503 を返してフロント側のバグを顕在化させる。
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/[^/]+\/balance-events$/,
    async (route: Route) => {
      const url = route.request().url()
      const m = url.match(/\/point-cards\/([^/]+)\/balance-events/)
      const cardId = m?.[1] ?? ''
      if (cardId === 'balance-events') {
        await route.fallback()
        return
      }
      const method = route.request().method()
      if (method === 'POST') {
        // 凍結中: バックエンドは 503 POINT_CARD_024 を返す
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'POINT_CARD_024',
              message: '残高機能は現在停止中です（資金決済法対応のため）',
            },
          }),
        })
        return
      }
      if (method === 'GET') {
        const items = state.events.filter(e => e.cardId === cardId)
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
}

test.describe('F18 SELF_ISSUED_BALANCE 凍結（資金決済法対応・2026-05-17〜）', () => {
  test('ADMIN が残高型カードを resolve してもタブが出ず、停止中バナーが表示される', async ({ page }) => {
    const state = newState()
    await setupMocks(page, state)

    // ─────────────────────────────────────────────
    // 1. 押印画面へ
    // ─────────────────────────────────────────────
    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/stamp`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 10_000 })

    // ─────────────────────────────────────────────
    // 2. BarcodeCapture の「手入力」タブからトークンを直接入力
    // ─────────────────────────────────────────────
    await page.getByRole('tab', { name: /手入力|Manual/i }).click()
    await page.locator('#bc-manual-value').fill(TOKEN)
    await page.getByRole('button', { name: /次へ|Next/i }).click()

    // ─────────────────────────────────────────────
    // 3. resolve 後、SELF_ISSUED_BALANCE であってもチャージ / 利用 / 返金タブは出ない
    //    （runtimeConfig.public.f18BalanceEnabled=false の凍結状態）
    // ─────────────────────────────────────────────
    // 解決済みカード情報は表示される（providerDisplayName）
    await expect(page.getByText('テスト店舗 残高型')).toBeVisible({ timeout: 10_000 })

    // 凍結バナー：i18n キー wallet.balance.disabled.banner
    await expect(page.getByText('この機能は現在停止中です')).toBeVisible()
    // 理由文：wallet.balance.disabled.reason
    await expect(
      page.getByText(/資金決済法対応のため.*一時停止/),
    ).toBeVisible()

    // チャージ / 利用 / 返金タブは描画されない
    await expect(page.getByRole('tab', { name: 'チャージ' })).toHaveCount(0)
    await expect(page.getByRole('tab', { name: '利用' })).toHaveCount(0)
    await expect(page.getByRole('tab', { name: '返金' })).toHaveCount(0)

    // バックエンド POST は一切走らない（凍結バナーで操作不可なため）
    expect(state.events.length).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────
// 機能再開時に復活させる（v2 / 法務整備完了後）
// 設計書 §16 v2 再開判断基準 5 項目（弁護士意見書 / 規約改訂 等）が全て満たされたら
// 下記 skip を解除し、上記凍結シナリオを削除して通常運用に戻すこと。
// ─────────────────────────────────────────────────────────────
test.describe.skip('F18 Phase 3 店主残高型カード操作フロー（凍結中はスキップ）', () => {
  test('ADMIN がトークン解決 → チャージ → 利用 → 返金まで一連の動線', async ({ page: _page }) => {
    // 機能再開時に元の charge → spend → refund シナリオを復活させる。
    // git 履歴の本コミット以前を参照。
  })
})
