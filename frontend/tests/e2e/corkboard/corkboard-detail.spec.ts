import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  CARD_ID_MEMO,
  CARD_ID_REFERENCE,
  CARD_ID_REFERENCE_DELETED,
  CARD_ID_URL,
  PERSONAL_BOARD_ID,
  buildBoardDetail,
  buildCard,
  mockBoardDetail,
  mockCardCrudApis,
  mockCatchAllApis,
  setupOwnerAuth,
} from './_helpers'

/**
 * F09.8 コルクボード Phase H — ボード詳細 E2E テスト。
 *
 * <p>シナリオ一覧:</p>
 * <ul>
 *   <li>CORK-DETAIL-001: ボード詳細ページが表示される</li>
 *   <li>CORK-DETAIL-002〜005: カード作成（MEMO / URL / REFERENCE / バリデーション）</li>
 *   <li>CORK-DETAIL-006: カード編集</li>
 *   <li>CORK-DETAIL-007: カード削除</li>
 *   <li>CORK-DETAIL-008: カードアーカイブ</li>
 *   <li>CORK-DETAIL-009: URL カードの OGP プレビュー表示</li>
 *   <li>CORK-DETAIL-010: REFERENCE カードのスナップショット表示</li>
 *   <li>CORK-DETAIL-011: REFERENCE 削除済みバッジ</li>
 *   <li>CORK-DETAIL-012〜014: ピン止め（追補）</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F09.8_corkboard.md / F09.8.1_corkboard_pin_dashboard.md</p>
 */

const DETAIL_URL = `/corkboard/${PERSONAL_BOARD_ID}`

test.describe('CORK-DETAIL: コルクボード詳細ページ', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-001
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-001: ボード詳細ページが表示される', async ({ page }) => {
    const board = buildBoardDetail([buildCard(CARD_ID_MEMO, { body: 'これは MEMO カードです' })])
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId('corkboard-detail-page')).toBeVisible({ timeout: 10_000 })
    // ヘッダーのタイトルとスコープバッジ
    await expect(page.getByRole('heading', { name: board.name })).toBeVisible()
    await expect(page.getByText('個人').first()).toBeVisible()
    // カード一覧
    await expect(page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)).toBeVisible()
    // 新規カードボタン
    await expect(page.getByTestId('corkboard-card-create-button')).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-002: MEMO 作成
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-002: MEMO 型カードを作成できる', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onCreatedCard: buildCard(999, {
        cardType: 'MEMO',
        title: '新しいメモ',
        body: 'メモ本文',
        colorLabel: 'YELLOW',
      }),
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('corkboard-card-create-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()

    // MEMO は default 種別なので、種別変更不要。
    // タイトル / 本文 / カラー入力
    await page.getByTestId('card-editor-title-input').fill('新しいメモ')
    await page.getByTestId('card-editor-body-input').fill('メモ本文')
    await page.getByTestId('card-editor-color-label-YELLOW').click()

    await page.getByTestId('card-editor-save-button').click()

    await expect(page.getByTestId('card-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.postedBody).toBeTruthy()
    const body = tracker.postedBody as Record<string, unknown>
    expect(body.cardType).toBe('MEMO')
    expect(body.title).toBe('新しいメモ')
    expect(body.body).toBe('メモ本文')
    expect(body.colorLabel).toBe('YELLOW')
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-003: URL 作成
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-003: URL 型カードを作成できる', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onCreatedCard: buildCard(999, { cardType: 'URL', url: 'https://example.com/' }),
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('corkboard-card-create-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()

    // 種別を URL に変更（PrimeVue Select）
    await selectCardType(page, 'リンク')

    await page.getByTestId('card-editor-url-input').fill('https://example.com/')
    await page.getByTestId('card-editor-save-button').click()

    await expect(page.getByTestId('card-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.postedBody).toBeTruthy()
    const body = tracker.postedBody as Record<string, unknown>
    expect(body.cardType).toBe('URL')
    expect(body.url).toBe('https://example.com/')
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-004: REFERENCE 作成
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-004: REFERENCE 型カードを作成できる', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onCreatedCard: buildCard(999, {
        cardType: 'REFERENCE',
        referenceType: 'TIMELINE_POST',
        referenceId: 9876,
      }),
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await page.getByTestId('corkboard-card-create-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()

    // 種別を REFERENCE に変更
    await selectCardType(page, '参照')

    // 参照先 ID を入力（PrimeVue InputNumber は内部で <input> を持つ）
    await fillReferenceId(page, 9876)

    await page.getByTestId('card-editor-save-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.postedBody).toBeTruthy()
    const body = tracker.postedBody as Record<string, unknown>
    expect(body.cardType).toBe('REFERENCE')
    expect(body.referenceType).toBe('TIMELINE_POST')
    expect(body.referenceId).toBe(9876)
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-005: バリデーション
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-005: 必須項目未入力でバリデーションエラー', async ({ page }) => {
    const board = buildBoardDetail([])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    // (1) MEMO で body を空のまま保存 → エラー
    await page.getByTestId('corkboard-card-create-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()
    await page.getByTestId('card-editor-save-button').click()
    // モーダルは閉じない
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()
    expect(tracker.postedBody).toBeNull()

    // (2) URL で url を空のまま保存 → エラー
    await selectCardType(page, 'リンク')
    await page.getByTestId('card-editor-save-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeVisible()
    expect(tracker.postedBody).toBeNull()
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-006: 編集
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-006: 既存カードを編集できる', async ({ page }) => {
    const existing = buildCard(CARD_ID_MEMO, {
      cardType: 'MEMO',
      title: '元タイトル',
      body: '元本文',
    })
    const board = buildBoardDetail([existing])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onUpdatedCard: { ...existing, title: '更新後タイトル' },
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    // ホバー / フォーカス時のメニュー表示。focus で表示される。
    const card = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await card.focus()
    await page.getByTestId(`corkboard-card-edit-button-${CARD_ID_MEMO}`).click()

    await expect(page.getByTestId('card-editor-modal')).toBeVisible()
    // 既存値プリフィル
    await expect(page.getByTestId('card-editor-title-input')).toHaveValue('元タイトル')

    await page.getByTestId('card-editor-title-input').fill('更新後タイトル')
    await page.getByTestId('card-editor-save-button').click()
    await expect(page.getByTestId('card-editor-modal')).toBeHidden({ timeout: 5_000 })

    expect(tracker.putBody).toBeTruthy()
    const body = tracker.putBody as Record<string, unknown>
    expect(body.title).toBe('更新後タイトル')
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-007: 削除
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-007: カードを削除できる', async ({ page }) => {
    const existing = buildCard(CARD_ID_MEMO, { body: '消す対象' })
    const board = buildBoardDetail([existing])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const card = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await card.focus()
    await page.getByTestId(`corkboard-card-delete-button-${CARD_ID_MEMO}`).click()

    // ConfirmDialog の OK ボタンを押す
    const confirmAccept = page.getByRole('button', { name: /(はい|OK|Yes)/i }).first()
    await confirmAccept.click()

    // DELETE が呼ばれることを待つ
    await expect.poll(() => tracker.deleted, { timeout: 5_000 }).toBe(true)
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-008: アーカイブ
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-008: カードをアーカイブできる', async ({ page }) => {
    const existing = buildCard(CARD_ID_MEMO, { body: 'archive対象' })
    const board = buildBoardDetail([existing])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onArchivedCard: { ...existing, isArchived: true },
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const card = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await card.focus()
    await page.getByTestId(`corkboard-card-archive-button-${CARD_ID_MEMO}`).click()

    await expect.poll(() => tracker.archivedBody, { timeout: 5_000 }).not.toBeNull()
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-009: OGP プレビュー
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-009: URL カードの OGP プレビューが表示される', async ({ page }) => {
    const urlCard = buildCard(CARD_ID_URL, {
      cardType: 'URL',
      url: 'https://example.com/article',
      ogTitle: 'OGP テストタイトル',
      ogImageUrl: 'https://example.com/og.png',
      ogDescription: 'OGP 説明文',
    })
    const board = buildBoardDetail([urlCard])
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId(`card-ogp-preview-${CARD_ID_URL}`)).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('OGP テストタイトル')).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-010: スナップショット表示
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-010: REFERENCE カードのスナップショットが表示される', async ({ page }) => {
    const refCard = buildCard(CARD_ID_REFERENCE, {
      cardType: 'REFERENCE',
      referenceType: 'TIMELINE_POST',
      referenceId: 9876,
      title: 'スナップショットタイトル',
      contentSnapshot: '元コンテンツの本文抜粋テキスト',
    })
    const board = buildBoardDetail([refCard])
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(page.getByTestId(`card-snapshot-${CARD_ID_REFERENCE}`)).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('スナップショットタイトル')).toBeVisible()
    await expect(page.getByText('元コンテンツの本文抜粋テキスト')).toBeVisible()
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-011: 削除済みバッジ
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-011: 参照元削除済みバッジが表示される', async ({ page }) => {
    const refCard = buildCard(CARD_ID_REFERENCE_DELETED, {
      cardType: 'REFERENCE',
      referenceType: 'TIMELINE_POST',
      referenceId: 9876,
      title: '削除されたスレッド',
      contentSnapshot: '元の抜粋',
      isRefDeleted: true,
    })
    const board = buildBoardDetail([refCard])
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    await expect(
      page.getByTestId(`card-snapshot-deleted-badge-${CARD_ID_REFERENCE_DELETED}`),
    ).toBeVisible({ timeout: 10_000 })
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-012: ピン止め（追補）
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-012: 個人ボードでカードをピン止めできる', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, { body: 'pin対象', isPinned: false })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onPinnedCard: { id: CARD_ID_MEMO, isPinned: true, pinnedAt: '2026-05-03T14:23:00Z' },
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    const pinBtn = page.getByTestId(`corkboard-card-pin-button-${CARD_ID_MEMO}`)
    await expect(pinBtn).toBeVisible()
    await expect(pinBtn).toHaveAttribute('aria-pressed', 'false')
    await pinBtn.click()

    await expect.poll(() => tracker.pinBody, { timeout: 5_000 }).not.toBeNull()
    const body = tracker.pinBody as Record<string, unknown>
    expect(body.isPinned).toBe(true)

    // ローカル state がトグルされ、aria-pressed が true へ変化する
    await cardEl.focus()
    await expect(pinBtn).toHaveAttribute('aria-pressed', 'true')
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-013: ピン止め解除
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-013: 既ピンのカードのピン止めを解除できる', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, {
      body: 'unpin対象',
      isPinned: true,
      pinnedAt: '2026-05-03T14:23:00Z',
    })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    const tracker = await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      onPinnedCard: { id: CARD_ID_MEMO, isPinned: false, pinnedAt: null },
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    const pinBtn = page.getByTestId(`corkboard-card-pin-button-${CARD_ID_MEMO}`)
    await expect(pinBtn).toHaveAttribute('aria-pressed', 'true')
    await pinBtn.click()

    await expect.poll(() => tracker.pinBody, { timeout: 5_000 }).not.toBeNull()
    const body = tracker.pinBody as Record<string, unknown>
    expect(body.isPinned).toBe(false)
  })

  // ---------------------------------------------------------------------
  // CORK-DETAIL-014: 上限到達エラー
  // ---------------------------------------------------------------------
  test('CORK-DETAIL-014: ピン上限到達時に専用 toast が表示される', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, { body: 'limit対象', isPinned: false })
    const board = buildBoardDetail([card])
    await mockBoardDetail(page, board)
    await mockCardCrudApis(page, PERSONAL_BOARD_ID, {
      pinShouldFailLimit: true,
    })

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await page.getByTestId(`corkboard-card-pin-button-${CARD_ID_MEMO}`).click()

    // 上限 toast 文言（i18n: corkboard.pinLimitReached）
    await expect(
      page.getByText(/ピン止め枠が満杯/),
    ).toBeVisible({ timeout: 5_000 })
  })
})

test.describe('CORK-DETAIL: 共有ボードでは📌 ボタン非表示', () => {
  test.beforeEach(async ({ page }) => {
    await setupOwnerAuth(page)
    await mockCatchAllApis(page)
  })

  test('CORK-DETAIL-015: TEAM スコープボードでは pin ボタンが表示されない', async ({ page }) => {
    const card = buildCard(CARD_ID_MEMO, { body: 'team-card' })
    const board = buildBoardDetail([card], {
      scopeType: 'TEAM',
      scopeId: 1,
      ownerId: null,
    })
    await mockBoardDetail(page, board)

    await page.goto(DETAIL_URL)
    await waitForHydration(page)

    const cardEl = page.getByTestId(`corkboard-card-${CARD_ID_MEMO}`)
    await cardEl.focus()
    await expect(
      page.getByTestId(`corkboard-card-pin-button-${CARD_ID_MEMO}`),
    ).toHaveCount(0)
  })
})

// ---------------------------------------------------------------------------
// PrimeVue 入力ヘルパ
// ---------------------------------------------------------------------------

/**
 * PrimeVue Select は `data-pc-name="select"` のトリガーをクリックして
 * オーバーレイを開き、option をテキストで選択する。
 */
async function selectCardType(page: import('@playwright/test').Page, label: string) {
  // data-testid が attribute fallthrough で root に付与されている前提
  const trigger = page.getByTestId('card-editor-card-type-select')
  await trigger.click()
  // option はオーバーレイで body 直下に出る
  await page.getByRole('option', { name: label }).click()
}

/**
 * PrimeVue InputNumber に数値を入力する。
 * `data-testid` は root の wrapper に付くため、内部の input にフォーカスして fill する。
 */
async function fillReferenceId(page: import('@playwright/test').Page, value: number) {
  const wrapper = page.getByTestId('card-editor-reference-id-input')
  const input = wrapper.locator('input').first()
  await input.click()
  await input.fill(String(value))
  // PrimeVue InputNumber は blur で値確定するためフォーカスを外す
  await input.press('Tab')
}
