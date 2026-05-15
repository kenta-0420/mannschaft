import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F18 個人ポイントカードウォレット — E2E ハッピーパステスト。
 *
 * シナリオ:
 *   1. 規約未同意状態でウォレットを開く → TermsAcceptModal が出る
 *   2. モーダル本文を最下部までスクロール → 4 項目をチェック → 「同意して開始」
 *   3. カード追加: FAB → 自由入力 → カード名 + 手入力タブでバーコード値 + 形式 → 次へ → 保存
 *   4. ウォレット一覧に戻り、追加したカードが表示される
 *   5. グループタブへ切替 → 新規グループ → 名前 + カードチェック → 保存
 *   6. グループ編集ページから「提示モードを開始」
 *   7. 初回スクリーンキャプチャ警告を「了解しました」で閉じる
 *   8. canvas（バーコード描画）が見える
 *   9. Escape で提示モードを離脱
 *
 * バックエンド API（/api/v1/point-cards/*) は本陣（6A）の時点ではフルで実装済みかどうか
 * 流動的なため、シナリオを安定して通すために page.route で全てモックする。
 * 既存パターン（action-memo.spec.ts 等）と同じ方針。
 *
 * 認証は chromium プロジェクトの storageState に依存する（既ログイン状態）。
 */

const CARD_API = '**/api/v1/point-cards'
const PROVIDERS_API = '**/api/v1/point-cards/providers'
const SETTINGS_API = '**/api/v1/point-cards/settings'
const CARDS_API = '**/api/v1/point-cards'
const CARD_BY_ID_API = '**/api/v1/point-cards/*'
const GROUPS_API = '**/api/v1/point-cards/groups'
const GROUP_BY_ID_API = '**/api/v1/point-cards/groups/*'
const PRESENTATION_START_API = '**/api/v1/point-cards/groups/*/presentation-start'

interface MockCard {
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
}

interface MockGroup {
  id: string
  name: string
  emoji: string | null
  displayOrder: number
  cardIds: string[]
  createdAt: string
  updatedAt: string
}

interface MockState {
  settings: {
    isEnabled: boolean
    termsAcceptedAt: string | null
    termsVersion: string | null
    requireBiometricOnShow: boolean
  }
  cards: MockCard[]
  groups: MockGroup[]
  nextCardId: number
  nextGroupId: number
}

/** ApiResponse.of() 相当のラップ */
function wrap<T>(data: T): string {
  return JSON.stringify({ data })
}

function nowIso(): string {
  return new Date().toISOString()
}

function cardToListItem(c: MockCard) {
  return {
    id: c.id,
    providerId: null,
    providerCode: null,
    providerDisplayName: null,
    providerBrandColor: null,
    providerLogoUrl: null,
    displayName: c.displayName,
    last4: c.last4,
    barcodeFormat: c.barcodeFormat,
    favorite: c.favorite,
    displayOrder: c.displayOrder,
    lastUsedAt: c.lastUsedAt,
    createdAt: c.createdAt,
  }
}

function cardToDetail(c: MockCard) {
  return {
    id: c.id,
    providerId: null,
    providerCode: null,
    providerDisplayName: null,
    providerBrandColor: null,
    providerLogoUrl: null,
    providerMatched: false,
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

function groupToListItem(g: MockGroup, cardCount: number) {
  return {
    id: g.id,
    name: g.name,
    emoji: g.emoji,
    displayOrder: g.displayOrder,
    cardCount,
    createdAt: g.createdAt,
    updatedAt: g.updatedAt,
  }
}

function groupToDetail(g: MockGroup, cards: MockCard[]) {
  const byId = new Map(cards.map((c) => [c.id, c]))
  const items = g.cardIds
    .map((cardId, idx) => {
      const c = byId.get(cardId)
      if (!c) return null
      return {
        cardId: c.id,
        displayOrder: idx,
        displayName: c.displayName,
        nickname: c.nickname,
        barcodeValue: c.barcodeValue,
        barcodeFormat: c.barcodeFormat,
        last4: c.last4,
        providerId: null,
        providerCode: null,
        providerDisplayName: null,
        providerBrandColor: null,
        providerLogoUrl: null,
        providerMatched: false,
      }
    })
    .filter((x): x is NonNullable<typeof x> => x !== null)
  return {
    id: g.id,
    name: g.name,
    emoji: g.emoji,
    displayOrder: g.displayOrder,
    items,
    createdAt: g.createdAt,
    updatedAt: g.updatedAt,
  }
}

async function setupWalletMocks(page: Page, state: MockState) {
  // /providers — 空配列で十分（プリセットボタンを出さないだけ）
  await page.route(PROVIDERS_API, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap([]),
    })
  })

  // /settings — GET / PUT
  await page.route(SETTINGS_API, async (route: Route) => {
    const method = route.request().method()
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(state.settings),
      })
      return
    }
    if (method === 'PUT') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        isEnabled?: boolean
        termsVersion?: string
        requireBiometricOnShow?: boolean
      }
      if (typeof body.isEnabled === 'boolean') state.settings.isEnabled = body.isEnabled
      if (body.termsVersion) {
        state.settings.termsVersion = body.termsVersion
        state.settings.termsAcceptedAt = nowIso()
      }
      if (typeof body.requireBiometricOnShow === 'boolean') {
        state.settings.requireBiometricOnShow = body.requireBiometricOnShow
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(state.settings),
      })
      return
    }
    await route.fallback()
  })

  // /groups + /groups/{id} + /groups/{id}/presentation-start を先に登録
  // （/point-cards/groups は /point-cards/{id} と URL が紛らわしいので順序に注意）
  await page.route(PRESENTATION_START_API, async (route: Route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }
    const url = route.request().url()
    const match = url.match(/\/groups\/([^/]+)\/presentation-start/)
    const groupId = match?.[1] ?? ''
    const group = state.groups.find((g) => g.id === groupId)
    if (!group) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap(groupToDetail(group, state.cards)),
    })
  })

  await page.route(GROUP_BY_ID_API, async (route: Route) => {
    // presentation-start は上で先取りされる前提
    const url = route.request().url()
    const method = route.request().method()
    const match = url.match(/\/groups\/([^/?]+)(?:\?|$)/)
    const groupId = match?.[1] ?? ''
    const group = state.groups.find((g) => g.id === groupId)
    if (method === 'GET') {
      if (!group) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(groupToDetail(group, state.cards)),
      })
      return
    }
    if (method === 'PATCH' && group) {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        name?: string
        emoji?: string | null
        cardIds?: string[]
      }
      if (typeof body.name === 'string') group.name = body.name
      if (body.emoji !== undefined) group.emoji = body.emoji
      if (Array.isArray(body.cardIds)) group.cardIds = body.cardIds
      group.updatedAt = nowIso()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(groupToDetail(group, state.cards)),
      })
      return
    }
    if (method === 'DELETE' && group) {
      state.groups = state.groups.filter((g) => g.id !== groupId)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.fallback()
  })

  await page.route(GROUPS_API, async (route: Route) => {
    const method = route.request().method()
    if (method === 'GET') {
      const items = state.groups.map((g) => groupToListItem(g, g.cardIds.length))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(items),
      })
      return
    }
    if (method === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        name: string
        emoji?: string | null
        cardIds?: string[]
      }
      const id = `group-${state.nextGroupId++}`
      const now = nowIso()
      const group: MockGroup = {
        id,
        name: body.name,
        emoji: body.emoji ?? null,
        displayOrder: state.groups.length,
        cardIds: body.cardIds ?? [],
        createdAt: now,
        updatedAt: now,
      }
      state.groups.push(group)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: wrap(groupToDetail(group, state.cards)),
      })
      return
    }
    await route.fallback()
  })

  // 単体カード /{id} と /{id}/used — list より先に登録（より具体的なルートを先に）
  await page.route(CARD_BY_ID_API, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()

    // /used エンドポイント
    if (url.includes('/used')) {
      if (method === 'POST') {
        await route.fulfill({ status: 204, body: '' })
        return
      }
      await route.fallback()
      return
    }

    // /groups 系は GROUP_BY_ID_API / GROUPS_API のルートで処理する
    if (url.includes('/point-cards/groups')) {
      await route.fallback()
      return
    }
    // /settings / /providers も別ルートに任せる
    if (url.includes('/point-cards/settings') || url.includes('/point-cards/providers')) {
      await route.fallback()
      return
    }

    const match = url.match(/\/point-cards\/([^/?]+)(?:\?|$)/)
    const cardId = match?.[1] ?? ''
    const card = state.cards.find((c) => c.id === cardId)
    if (method === 'GET') {
      if (!card) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: wrap(cardToDetail(card)),
      })
      return
    }
    await route.fallback()
  })

  // カード一覧 + 作成
  await page.route(CARDS_API, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()
    // /groups 系・/providers・/settings は除外（より具体的なルートに任せる）
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
        body: wrap(state.cards.map(cardToListItem)),
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
      const id = `card-${state.nextCardId++}`
      const now = nowIso()
      const card: MockCard = {
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
      }
      state.cards.push(card)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: wrap(cardToDetail(card)),
      })
      return
    }
    await route.fallback()
  })
}

function newState(): MockState {
  return {
    settings: {
      isEnabled: false,
      termsAcceptedAt: null,
      termsVersion: null,
      requireBiometricOnShow: false,
    },
    cards: [],
    groups: [],
    nextCardId: 1,
    nextGroupId: 1,
  }
}

test.describe('F18 ポイントカードウォレット', () => {
  test('ハッピーパス: 規約同意 → カード追加 → グループ作成 → 提示モード起動 → Escape 終了', async ({
    page,
  }) => {
    const state = newState()
    await setupWalletMocks(page, state)

    // ─────────────────────────────────────────────
    // 1. ウォレットページへ
    // ─────────────────────────────────────────────
    await page.goto('/wallet')
    await waitForHydration(page)

    // ─────────────────────────────────────────────
    // 2. 規約モーダルを処理
    // ─────────────────────────────────────────────
    await expect(page.getByText('ご利用前の同意事項')).toBeVisible()

    // モーダル本文を最下部までスクロール（4 項目チェックボックスが enable される条件）
    await page.evaluate(() => {
      const scrollables = document.querySelectorAll<HTMLElement>(
        '[role="dialog"] *',
      )
      for (const el of Array.from(scrollables)) {
        if (el.scrollHeight > el.clientHeight) {
          el.scrollTop = el.scrollHeight
          el.dispatchEvent(new Event('scroll', { bubbles: true }))
        }
      }
    })

    // 4 項目をチェック
    const dialog = page.getByRole('dialog', { name: 'ご利用前の同意事項' })
    const checkboxes = dialog.locator('input[type="checkbox"]')
    const count = await checkboxes.count()
    expect(count).toBeGreaterThanOrEqual(4)
    for (let i = 0; i < Math.min(count, 4); i++) {
      await checkboxes.nth(i).check({ force: true })
    }

    await page.getByRole('button', { name: '同意して開始' }).click()

    // ─────────────────────────────────────────────
    // 3. ウォレットホームが表示される
    // ─────────────────────────────────────────────
    await expect(
      page.getByRole('heading', { name: 'ポイントカードウォレット' }),
    ).toBeVisible()
    // まだカードが無いので「まだカードが登録されていません」が出る
    await expect(page.getByText('まだカードが登録されていません')).toBeVisible()

    // ─────────────────────────────────────────────
    // 4. カード追加: FAB（aria-label="カードを追加"）
    // ─────────────────────────────────────────────
    await page.getByRole('link', { name: 'カードを追加' }).click()
    await page.waitForURL('**/wallet/cards/new')

    // カード名を入力
    await page.locator('#card-displayname').fill('テストカード')

    // 手入力タブに切り替え
    await page.getByRole('tab', { name: '手入力' }).click()
    await page.locator('#bc-manual-value').fill('1234567890123')
    await page.locator('#bc-manual-format').selectOption('EAN13')

    // 手入力タブの「次へ」ボタンで detected emit → 親の barcodeValue/format がセット
    await page
      .locator('section')
      .filter({ has: page.locator('#bc-manual-value') })
      .getByRole('button', { name: '次へ' })
      .click()

    // 親フッタの「次へ」でステップ 2（プレビュー）へ
    await page.locator('.card-new__footer').getByRole('button', { name: '次へ' }).click()

    // プレビュー画面で「保存」
    await page.getByRole('button', { name: '保存' }).click()

    // カード詳細ページへ遷移する（/wallet/cards/{id}）
    await page.waitForURL(/\/wallet\/cards\/card-\d+/)

    // ─────────────────────────────────────────────
    // 5. ウォレット一覧に戻ってカードが見える
    // ─────────────────────────────────────────────
    await page.goto('/wallet')
    await waitForHydration(page)
    await expect(page.getByText('テストカード')).toBeVisible()

    // ─────────────────────────────────────────────
    // 6. グループタブへ
    // ─────────────────────────────────────────────
    await page.getByRole('tab', { name: 'グループ' }).click()
    await expect(page.getByText('まだグループがありません')).toBeVisible()

    // 7. 新規グループ作成リンク
    await page.getByRole('link', { name: /新規グループ/ }).click()
    await page.waitForURL('**/wallet/groups/new')

    await page.locator('#group-name').fill('テストグループ')

    // カード一覧のチェックボックス（テストカード行）
    const cardRow = page.locator('label').filter({ hasText: 'テストカード' })
    await cardRow.locator('input[type="checkbox"]').check()

    // 保存
    await page.getByRole('button', { name: '保存' }).click()

    // ─────────────────────────────────────────────
    // 8. グループ編集ページに遷移
    // ─────────────────────────────────────────────
    await page.waitForURL(/\/wallet\/groups\/group-\d+$/)
    await expect(page.getByRole('heading', { name: 'グループを編集' })).toBeVisible()

    // 9. 提示モードを開始
    await page.getByRole('button', { name: /提示モードを開始/ }).click()
    await page.waitForURL(/\/wallet\/groups\/group-\d+\/show/)

    // 10. スクリーンキャプチャ警告を閉じる（初回オープン時のみ・localStorage で永続）
    const warningAck = page.getByRole('button', { name: '了解しました' })
    if (await warningAck.isVisible({ timeout: 3000 }).catch(() => false)) {
      await warningAck.click()
    }

    // 11. canvas（バーコード描画）が DOM 上に存在する
    //     JsBarcode は HTMLCanvasElement に描画するため、描画完了を待たずとも要素は存在する
    await expect(page.locator('canvas').first()).toBeVisible({ timeout: 10_000 })

    // 12. Escape で提示モードを離脱 → グループ編集ページへ戻る
    await page.keyboard.press('Escape')
    await page.waitForURL(/\/wallet\/groups\/group-\d+$/, { timeout: 10_000 })
  })
})
