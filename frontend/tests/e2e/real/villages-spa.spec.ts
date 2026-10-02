/**
 * F17 村コミュニティ — 村タブ「永続シェル方式（SPA）」の挙動検証（VLG-SPA-001〜004）。
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動済みの状態で実行してください。
 *
 * 別ファイルに分離した理由:
 *   本来は villages.spec.ts の「VLG-001〜012」describe と同一ファイル・別 describe
 *   だったが、同一ファイル内で 2 つの describe がそれぞれ BrowserContext を
 *   beforeAll/afterAll で開閉すると、片方の後始末がもう片方に干渉し
 *   （`Target page, context or browser has been closed`）、片方だけ単独実行すると
 *   通るのに通しで実行すると失敗する現象が実測された。
 *   このプロジェクトの単一セッション設計（1セッション=1 BrowserContext）を
 *   ファイル単位で素直に体現するため、「1 スペックファイル = 1 セッション（1
 *   context）」に揃えてファイルごと分離した。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（単一セッション設計）。
 * describe 内で新規ログインは行わない — アクセストークンはリフレッシュの度に
 * サーバ側で「回転」（旧トークンを revoke し後継を発行）するが、後継トークンは
 * Cookie 経由でその場の BrowserContext にしか残らない。テストが新しい
 * storageState スナップショット/新規ログインから始まると、既に revoke 済みの
 * トークンを再提示することになり、grace window（60秒）超過後は「リプレイ攻撃」
 * として検出されセッションごと失効する（AuthTokenRotationService）。
 * そのため beforeAll で 1 つの BrowserContext を作成し、全テストで使い回す
 * （mode: 'serial' で順序も固定）。
 *
 * ただし共有するのは BrowserContext（Cookie ジャー）までで、page はテストごとに
 * beforeEach で newPage() → afterEach で close() する。回転後トークンの継続性に
 * 必要なのは Cookie ジャーであって page 自体の使い回しではなく、page を使い回すと
 * 前のテストが張った WebSocket 接続（presence 等）や DOM 状態が次のテストに漏れ、
 * ERR_ABORTED 等の干渉を起こすため。
 *
 * 前提シード: backend/scripts/seed-e2e-data.js の F17 ブロックを実行済み。
 *   - villages 2 件（"E2Eテスト公式村" OFFICIAL / "E2Eテストコミュニティ村" COMMUNITY）
 *   - E2E_USER は COMMUNITY 村に VILLAGER として参加済み
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 *
 * 永続シェル方式（pages/villages/[id].vue + 子タブ）への改修で満たすべき受け入れ条件:
 *   AC-1  タブをクリックすると URL が /villages/{id}/{tab} に更新され、戻る操作で戻れる
 *   AC-2  タブ遷移中に全画面ローディング（.pi-spin が画面全体を覆う状態）が出ない
 *   AC-4  村名 h1（VillageHeader）が遷移前後で同一 DOM のまま（再マウントされない）
 *   AC-5  /api/v1/villages/{id}（getVillage）がタブ遷移で追加発火しない（再フェッチ無し）
 *   AC-6  全 9 タブそれぞれのパネル主要要素が可視（空/エラーでない）
 *
 *   page.goto ではなく VillageHeader のタブを **クリック** して SPA 遷移させる点が肝。
 */

import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const COMMUNITY_VILLAGE_NAME = 'E2Eテストコミュニティ村'

test.describe('VLG-SPA-001〜006: 村タブ永続シェル（SPA）', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(120_000)

  let context: BrowserContext
  let page: Page
  let cachedVillageId = ''

  // 村ヘッダー上のタブ表示名（ja ロケール）。VillageHeader.vue の tab.i18nKey に対応。
  const TAB_LABELS = {
    bulletin: '掲示板',
    timeline: 'タイムライン',
    lobby: 'ロビー',
    members: '村人一覧',
    calendar: '歳時記',
    festival: 'お祭り',
    matchRecruit: '練習試合・募集',
    meetup: '寄合',
    chronicle: '村史',
  } as const

  // タブ表示名 → URL 末尾セグメント（VillageHeader.vue の tab.to 末尾に対応）。
  // clickTab は表示名でなく href サフィックスでロケートするため、ここで対応付けする。
  // 表示名だけだと「タイムライン」等がグローバルナビの同名リンク（/timeline 等）と
  // 衝突し誤クリックするため、村ヘッダー内の href サフィックスで一意に特定する。
  const LABEL_TO_SLUG: Record<string, string> = {
    [TAB_LABELS.bulletin]: 'bulletin',
    [TAB_LABELS.timeline]: 'timeline',
    [TAB_LABELS.lobby]: 'lobby',
    [TAB_LABELS.members]: 'members',
    [TAB_LABELS.calendar]: 'calendar',
    [TAB_LABELS.festival]: 'festivals',
    [TAB_LABELS.matchRecruit]: 'match-recruits',
    [TAB_LABELS.meetup]: 'meetups',
    [TAB_LABELS.chronicle]: 'chronicles',
  }

  test.beforeAll(async ({ browser }) => {
    context = await browser.newContext({ storageState: 'tests/e2e/.auth/real-user.json' })

    // token/villageId 解決専用の一時 page（beforeEach 前なので明示的に作って閉じる）。
    const setupPage = await context.newPage()
    try {
      // page.request は共有 BrowserContext の Cookie（access_token）をそのまま使うため、
      // 別途ログインしない（新規ログインは回転済みトークンの再提示問題を誘発するため禁止）。
      const searchResp = await setupPage.request.get(
        `http://localhost:8080/api/v1/villages/search?q=${encodeURIComponent(COMMUNITY_VILLAGE_NAME)}&size=10`,
      )
      expect(searchResp.status()).toBe(200)
      const searchBody = await searchResp.json()
      const village = searchBody.content.find(
        (v: { name: string, id: string }) => v.name === COMMUNITY_VILLAGE_NAME,
      )
      expect(village, `seed 済の "${COMMUNITY_VILLAGE_NAME}" が search 結果に含まれていない`).toBeTruthy()
      cachedVillageId = village.id as string
    }
    finally {
      await setupPage.close()
    }
  })

  test.beforeEach(async () => {
    page = await context.newPage()
  })

  test.afterEach(async () => {
    await page.close()
  })

  // 本ファイル自前の context は自前の afterAll でのみ閉じる（他ファイルの describe と
  // 混じらないよう、1 スペックファイル = 1 セッションで完結させる）。
  test.afterAll(async () => {
    await context.close()
  })

  /**
   * VillageHeader のタブ NuxtLink をクリックする。
   *  グローバルナビの同名リンク（例: 「タイムライン」→ /timeline）を誤クリックしないよう
   *  村ヘッダー（.village-header）内に **スコープ** し、href サフィックスでタブを一意に特定する。
   */
  async function clickTab(page: Page, label: string): Promise<void> {
    const slug = LABEL_TO_SLUG[label]
    if (!slug) {
      throw new Error(`未知のタブ表示名: ${label}（LABEL_TO_SLUG に未登録）`)
    }
    await page.locator(`.village-header a[href$="/${slug}"]`).first().click()
  }

  // -------------------------------------------------------------------------
  // VLG-SPA-001 (AC-1): タブクリックで URL 更新 + 戻る操作で戻れる
  // -------------------------------------------------------------------------
  test('VLG-SPA-001: タブクリックで URL が更新され goBack() で戻れる', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    // ローディングスピナーは出ないこともある（描画が速い場合）。出ている時だけ消えるまで待つ。
    // 消えないまま固まるのは本物の不具合なので、待ち切れなければテストを失敗させる（握りつぶさない）。
    const spinner = page.locator('.pi-spin')
    if (await spinner.count() > 0) {
      await spinner.first().waitFor({ state: 'detached', timeout: 30_000 })
    }
    await expect(page).toHaveURL(new RegExp(`/villages/${villageId}/bulletin`))

    // タイムラインタブへクリック遷移 → URL が /timeline に更新
    await clickTab(page, TAB_LABELS.timeline)
    await expect(page).toHaveURL(new RegExp(`/villages/${villageId}/timeline`), { timeout: 15_000 })

    // 戻る → bulletin へ
    await page.goBack()
    await expect(page).toHaveURL(new RegExp(`/villages/${villageId}/bulletin`), { timeout: 15_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-SPA-002 (AC-2 + AC-4): 遷移中に全画面ローディングが出ず、村名 h1 が据置
  // -------------------------------------------------------------------------
  test('VLG-SPA-002: タブ遷移で白画面ローディングが出ず村名 h1 が再マウントされない', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    // ローディングスピナーは出ないこともある（描画が速い場合）。出ている時だけ消えるまで待つ。
    // 消えないまま固まるのは本物の不具合なので、待ち切れなければテストを失敗させる（握りつぶさない）。
    const spinner = page.locator('.pi-spin')
    if (await spinner.count() > 0) {
      await spinner.first().waitFor({ state: 'detached', timeout: 30_000 })
    }

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    await expect(headline).toBeVisible({ timeout: 30_000 })
    // AC-4: 遷移前の h1 の elementHandle を掴んでおく
    const beforeHandle = await headline.elementHandle()

    // タブを次々にクリックしつつ、全画面ローディングが出ないことを確認
    for (const label of [TAB_LABELS.timeline, TAB_LABELS.calendar, TAB_LABELS.festival]) {
      await clickTab(page, label)
      // AC-2: PageLoading（全画面 .pi-spin を内包する固定オーバーレイ）が出ない。
      //   ヘッダ常駐 + パネル差替のため、村名 h1 はずっと可視のまま。
      await expect(headline).toBeVisible()
      // 子パネル側のスピナーは一瞬出ても良いが、村名見出しが消える=全体再マウントは不可。
    }

    // AC-4: 一連の遷移後も同一の h1 DOM ノードが生きている（再マウントされていない）
    const afterHandle = await headline.elementHandle()
    const sameNode = await page.evaluate(
      ([a, b]) => a === b,
      [beforeHandle, afterHandle],
    )
    expect(sameNode, '村名 h1 が遷移で再マウントされている（永続シェルが効いていない）').toBe(true)
  })

  // -------------------------------------------------------------------------
  // VLG-SPA-003 (AC-5): タブ遷移で getVillage が追加発火しない
  // -------------------------------------------------------------------------
  test('VLG-SPA-003: タブ遷移で GET /villages/{id} が増えない（再フェッチ無し）', async () => {
    const villageId = cachedVillageId

    // getVillage = GET /api/v1/villages/{id}（末尾が id そのもの。サブパス /memberships 等は除外）
    const getVillageRe = new RegExp(`/api/v1/villages/${villageId}(?:\\?|$)`)
    let getVillageCount = 0
    page.on('request', (req) => {
      if (req.method() === 'GET' && getVillageRe.test(req.url())) {
        getVillageCount += 1
      }
    })

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    // ローディングスピナーは出ないこともある（描画が速い場合）。出ている時だけ消えるまで待つ。
    // 消えないまま固まるのは本物の不具合なので、待ち切れなければテストを失敗させる（握りつぶさない）。
    const spinner = page.locator('.pi-spin')
    if (await spinner.count() > 0) {
      await spinner.first().waitFor({ state: 'detached', timeout: 30_000 })
    }
    await expect(page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()).toBeVisible({ timeout: 30_000 })

    const afterInitial = getVillageCount

    // タブを複数切替（クリック SPA 遷移）
    for (const label of [TAB_LABELS.members, TAB_LABELS.calendar, TAB_LABELS.meetup, TAB_LABELS.bulletin]) {
      await clickTab(page, label)
      await page.waitForTimeout(400)
    }

    // 永続シェルは村を 1 度だけ取得。タブ遷移では getVillage が増えてはならない。
    expect(getVillageCount).toBe(afterInitial)
  })

  // -------------------------------------------------------------------------
  // VLG-SPA-004 (AC-6): 全 9 タブのパネル主要要素が可視
  // -------------------------------------------------------------------------
  test('VLG-SPA-004: 全 9 タブをクリック巡回し各パネルが描画される', async () => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    // ローディングスピナーは出ないこともある（描画が速い場合）。出ている時だけ消えるまで待つ。
    // 消えないまま固まるのは本物の不具合なので、待ち切れなければテストを失敗させる（握りつぶさない）。
    const spinner = page.locator('.pi-spin')
    if (await spinner.count() > 0) {
      await spinner.first().waitFor({ state: 'detached', timeout: 30_000 })
    }

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    await expect(headline).toBeVisible({ timeout: 30_000 })

    const order: Array<[string, string]> = [
      [TAB_LABELS.timeline, 'timeline'],
      [TAB_LABELS.lobby, 'lobby'],
      [TAB_LABELS.members, 'members'],
      [TAB_LABELS.calendar, 'calendar'],
      [TAB_LABELS.festival, 'festivals'],
      [TAB_LABELS.matchRecruit, 'match-recruits'],
      [TAB_LABELS.meetup, 'meetups'],
      [TAB_LABELS.chronicle, 'chronicles'],
      [TAB_LABELS.bulletin, 'bulletin'],
    ]

    for (const [label, slug] of order) {
      await clickTab(page, label)
      await expect(page).toHaveURL(new RegExp(`/villages/${villageId}/${slug}`), { timeout: 15_000 })
      // ヘッダは据置。パネル本体（main 内の何らかの要素）が描画されていること。
      await expect(headline).toBeVisible()
      await expect(page.locator('main').first()).toBeVisible()
    }
  })
})
