import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F18 Phase 3 — 店主残高型カード操作フロー E2E。
 *
 * シナリオ（API モック方式、wallet-org-stamp.spec.ts と同じ流儀）:
 *   1. 組織 ADMIN として `/organizations/{orgId}/admin/point-cards/stamp` を開く
 *   2. QR モードのまま BarcodeCapture の「手入力」タブで顧客一時トークン UUID を入力
 *      → submitManual で stamp.vue 側の onQrDetected が呼ばれ、
 *        resolveByToken モックが SELF_ISSUED_BALANCE で応答
 *   3. SELF_ISSUED_BALANCE のため操作タブが「チャージ / 利用 / 返金」に切替わる
 *   4. 1,000 円チャージ → 残高 ¥1,000
 *   5. 利用タブで 500 円 → 残高 ¥500
 *   6. 返金タブで元 SPENT を選択 → 200 円返金 → 残高 ¥700
 *   7. モック側で 3 件の event が記録されている
 *
 * バックエンド API はすべて page.route でモック化する。
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

function nowIso(): string {
  return new Date().toISOString()
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

  // 残高変動イベント（POST/GET）
  await page.route(
    /\/api\/v1\/organizations\/\d+\/point-cards\/[^/]+\/balance-events$/,
    async (route: Route) => {
      const url = route.request().url()
      const m = url.match(/\/point-cards\/([^/]+)\/balance-events/)
      const cardId = m?.[1] ?? ''
      // 組織全体の履歴 URL（cardId 部 = "balance-events"）は別ハンドラへ
      if (cardId === 'balance-events') {
        await route.fallback()
        return
      }
      const method = route.request().method()
      if (method === 'POST') {
        const body = JSON.parse(route.request().postData() ?? '{}') as {
          operationType: 'CHARGE' | 'SPENT' | 'REFUND'
          amount: number
          note?: string
          refundOfEventId?: string
        }
        const sign = body.operationType === 'SPENT' ? -1 : 1
        const signedDelta = sign * body.amount
        state.balance = Math.round((state.balance + signedDelta) * 100) / 100
        const ev: MockBalanceEvent = {
          id: `evt-${state.nextEventSeq++}`,
          cardId,
          providerId: PROVIDER_ID,
          providerDisplayName: 'テスト店舗 残高型',
          organizationId: ORG_ID,
          operationType: body.operationType,
          delta: signedDelta.toFixed(2),
          balanceAfter: state.balance.toFixed(2),
          refundOfEventId: body.refundOfEventId ?? null,
          operatedByUserId: 1,
          operatedByUserDisplayName: '店主太郎',
          operatedAt: nowIso(),
          note: body.note ?? null,
          createdAt: nowIso(),
        }
        state.events.unshift(ev)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: wrap(ev),
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

test.describe('F18 Phase 3 店主残高型カード操作フロー', () => {
  test('ADMIN がトークン解決 → チャージ → 利用 → 返金まで一連の動線', async ({ page }) => {
    const state = newState()
    await setupMocks(page, state)

    // ─────────────────────────────────────────────
    // 1. 押印画面へ
    // ─────────────────────────────────────────────
    await page.goto(`/organizations/${ORG_ID}/admin/point-cards/stamp`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 10_000 })

    // ─────────────────────────────────────────────
    // 2. BarcodeCapture の「手入力」タブに切替えて、トークンを直接入力する
    //    （カメラは E2E では使えないため、手入力タブの submitManual を
    //     経由して onQrDetected を発火させる）
    // ─────────────────────────────────────────────
    // QR モードの BarcodeCapture が表示されている前提
    // 手入力タブを開く
    await page.getByRole('tab', { name: /手入力|Manual/i }).click()
    await page.locator('#bc-manual-value').fill(TOKEN)
    // 「次へ」ボタンで submitManual → onQrDetected → resolveByToken
    await page.getByRole('button', { name: /次へ|Next/i }).click()

    // ─────────────────────────────────────────────
    // 3. resolve 完了で SELF_ISSUED_BALANCE のタブ（チャージ）が出る
    // ─────────────────────────────────────────────
    const chargeTab = page.getByRole('tab', { name: 'チャージ' })
    await expect(chargeTab).toBeVisible({ timeout: 10_000 })

    // ─────────────────────────────────────────────
    // 4. チャージ 1,000 円
    // ─────────────────────────────────────────────
    await chargeTab.click()
    await page.locator('#charge-amount').fill('1000')
    await page.getByRole('button', { name: /^チャージ/ }).last().click()

    // 残高 ¥1,000 を確認
    await expect(page.getByText('¥1,000')).toBeVisible({ timeout: 5_000 })

    // ─────────────────────────────────────────────
    // 5. 利用 500 円
    // ─────────────────────────────────────────────
    await page.getByRole('tab', { name: '利用' }).click()
    await page.locator('#spend-amount').fill('500')
    await page.getByRole('button', { name: /^利用/ }).last().click()

    await expect(page.getByText('¥500')).toBeVisible({ timeout: 5_000 })

    // ─────────────────────────────────────────────
    // 6. 返金 200 円（元 SPENT を選択）
    // ─────────────────────────────────────────────
    await page.getByRole('tab', { name: '返金' }).click()
    const refundEvent = page.locator('#refund-event')
    await expect(refundEvent).toBeEnabled({ timeout: 5_000 })
    // 空 option / 区切りを除いた最初の有効値を選ぶ
    const optionValue = await refundEvent.evaluate((el) => {
      const sel = el as HTMLSelectElement
      for (const o of Array.from(sel.options)) {
        if (o.value && o.value.length > 0) return o.value
      }
      return ''
    })
    expect(optionValue).not.toBe('')
    await refundEvent.selectOption(optionValue)
    await page.locator('#refund-amount').fill('200')
    await page.getByRole('button', { name: /^返金/ }).last().click()

    // 残高 ¥700 を確認
    await expect(page.getByText('¥700')).toBeVisible({ timeout: 5_000 })

    // ─────────────────────────────────────────────
    // 7. モック側で 3 件の event が記録されている
    // ─────────────────────────────────────────────
    expect(state.events.length).toBe(3)
    expect(state.events.map(e => e.operationType).sort()).toEqual(['CHARGE', 'REFUND', 'SPENT'])
  })
})
