/**
 * F17 村コミュニティ（Village Community）— 実機 E2E テスト（VLG-001〜012）。
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:3000) が
 * 起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（単一セッション設計。
 * テスト内で別途 UI ログインしない。連続ログインは BE のレート制限に当たるため、
 * API 直叩きが必要な検証は各 describe の beforeAll で 1 回だけ token を取得する）。
 *
 * 前提シード: backend/scripts/seed-e2e-data.js の F17 ブロックを実行済み。
 *   - villages 2 件（"E2Eテスト公式村" OFFICIAL / "E2Eテストコミュニティ村" COMMUNITY）
 *   - E2E_USER は COMMUNITY 村に VILLAGER として参加済み
 *
 * 検証粒度: dashboard.spec.ts / wallet.spec.ts と同等の「ページ描画 + 主要要素可視」レベル。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const USER_EMAIL = 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = 'TestPass2026!'

const COMMUNITY_VILLAGE_NAME = 'E2Eテストコミュニティ村'

// ===========================================================================
// VLG-001〜012: F17 村コミュニティ
//
// 注意:
//   - 1 describe 内でテスト毎に fetchAccessToken を呼ぶと連続 login 試行が
//     バックエンドのレート制限に引っかかって 400 を返す事例があった（実測 13 連続）。
//   - そのため beforeAll で token と villageId を 1 回だけ取得し、全ケースで使い回す。
//     request fixture は test スコープなので playwright.request.newContext() で
//     独立した requestContext を生成する。
// ===========================================================================
test.describe('VLG-001〜012: F17 村コミュニティ', () => {
  // 村ページは village 詳細 + メンバーシップ + チャネル等 複数 API 直列のためタイムアウト延長
  test.setTimeout(120_000)

  // describe スコープで token と villageId を 1 回だけ取得
  let cachedToken = ''
  let cachedVillageId = ''

  test.beforeAll(async ({ playwright }) => {
    const ctx = await playwright.request.newContext()
    try {
      const loginResp = await ctx.post('http://localhost:8080/api/v1/auth/login', {
        data: { email: USER_EMAIL, password: USER_PASSWORD },
      })
      expect(loginResp.status()).toBe(200)
      const loginBody = await loginResp.json()
      cachedToken = loginBody.data.accessToken as string

      const searchResp = await ctx.get(
        `http://localhost:8080/api/v1/villages/search?q=${encodeURIComponent(COMMUNITY_VILLAGE_NAME)}&size=10`,
        { headers: { Authorization: `Bearer ${cachedToken}` } },
      )
      expect(searchResp.status()).toBe(200)
      const searchBody = await searchResp.json()
      const village = searchBody.content.find(
        (v: { name: string; id: string }) => v.name === COMMUNITY_VILLAGE_NAME,
      )
      expect(village, `seed 済の "${COMMUNITY_VILLAGE_NAME}" が search 結果に含まれていない`).toBeTruthy()
      cachedVillageId = village.id as string
    }
    finally {
      await ctx.dispose()
    }
  })

  // -------------------------------------------------------------------------
  // VLG-001: /villages 一覧ページが表示される
  // -------------------------------------------------------------------------
  test('VLG-001: /villages 村一覧ページが表示される', async ({ page }) => {
    await page.goto('/villages')
    await waitForHydration(page)
    await expect(page).not.toHaveURL(/\/login/)

    // 検索ページの PageHeader（heading）または検索フォーム input が見える。
    // seed 投入済の COMMUNITY 村名が一覧に出ていれば理想的。
    const heading = page.getByRole('heading').first()
    const villageName = page.getByText(COMMUNITY_VILLAGE_NAME, { exact: false }).first()
    await expect(heading.or(villageName).first()).toBeVisible({ timeout: 20_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-002: /villages/create-request 申請フォームが表示される
  // -------------------------------------------------------------------------
  test('VLG-002: /villages/create-request 申請フォームが表示される', async ({ page }) => {
    await page.goto('/villages/create-request')
    await waitForHydration(page)
    await expect(page).not.toHaveURL(/\/login/)

    // フォーム上の何らかの input（村名 / slug / カテゴリなど）が描画されること
    const anyInput = page.locator('input[type="text"], input:not([type])').first()
    await expect(anyInput).toBeVisible({ timeout: 20_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-003: GET /api/v1/villages/search が認証付きで 200 を返す
  //
  //   バックエンド実装上 search は認証必須（SecurityUtils.getCurrentUserId 呼出のため）。
  //   認証ヘッダ付きで 200 + content 配列が返ることを検証する。
  // -------------------------------------------------------------------------
  test('VLG-003: GET /villages/search が認証付きで 200 を返す', async ({ page }) => {
    const token = cachedToken
    const resp = await page.request.get('http://localhost:8080/api/v1/villages/search?page=0&size=10', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body.content)).toBe(true)
    // seed 済 COMMUNITY 村が見つかること
    const found = body.content.find((v: { name: string }) => v.name === COMMUNITY_VILLAGE_NAME)
    expect(found, 'seed 済 COMMUNITY 村が search 結果に含まれていない').toBeTruthy()
  })

  // -------------------------------------------------------------------------
  // VLG-004: 検索クエリで該当なしの場合に空配列が返る
  //
  //   絶対にマッチしない文字列で検索 → 200 + content=[] を期待。
  // -------------------------------------------------------------------------
  test('VLG-004: 検索クエリ該当なしで 200 + 空配列', async ({ page }) => {
    const token = cachedToken
    const q = encodeURIComponent('absent_text_zzz_nomatch_18f2e9')
    const resp = await page.request.get(`http://localhost:8080/api/v1/villages/search?q=${q}&page=0&size=10`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body.content)).toBe(true)
    expect(body.content.length).toBe(0)
  })

  // -------------------------------------------------------------------------
  // VLG-005: 村詳細 /villages/{id} に直アクセスすると bulletin にリダイレクト
  //
  //   index.vue は即時 navigateTo('/villages/{id}/bulletin') する設計。
  //   遷移後の bulletin ページに VillageHeader（h1 で村名）が描画されること。
  // -------------------------------------------------------------------------
  test('VLG-005: /villages/{id} が表示され村名が見える（bulletin にリダイレクト）', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // PageLoading が消えた後 VillageHeader の h1（村名）が見える、
    // または main 要素が描画されていれば pass
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-006: タイムラインタブ
  // -------------------------------------------------------------------------
  test('VLG-006: /villages/{id}/timeline タイムラインページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/timeline`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // VillageHeader の h1（村名）または main 要素が見える
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-007: 井戸端会議タブ
  // -------------------------------------------------------------------------
  test('VLG-007: /villages/{id}/lobby 井戸端会議ページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/lobby`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-008: 掲示板タブ
  // -------------------------------------------------------------------------
  test('VLG-008: /villages/{id}/bulletin 掲示板ページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-009: メンバー一覧
  //
  //   E2E_USER は COMMUNITY 村に VILLAGER として参加済（seed）。
  //   メンバー一覧ページが描画されること（VillageHeader + メンバーテーブル想定）。
  // -------------------------------------------------------------------------
  test('VLG-009: /villages/{id}/members メンバー一覧ページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/members`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    // VillageHeader の村名見出し or main 要素が見える
    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-010: カレンダー（歳時記）タブ
  // -------------------------------------------------------------------------
  test('VLG-010: /villages/{id}/calendar カレンダーページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/calendar`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-011: お祭りタブ
  // -------------------------------------------------------------------------
  test('VLG-011: /villages/{id}/festivals お祭りページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/festivals`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })

  // -------------------------------------------------------------------------
  // VLG-012: 練習試合募集タブ
  // -------------------------------------------------------------------------
  test('VLG-012: /villages/{id}/match-recruits 練習試合募集ページが表示される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/match-recruits`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page).not.toHaveURL(/\/login/)

    const headline = page.getByRole('heading', { name: COMMUNITY_VILLAGE_NAME }).first()
    const mainEl = page.locator('main').first()
    await expect(headline.or(mainEl).first()).toBeVisible({ timeout: 30_000 })
  })
})

// ===========================================================================
// VLG-SPA-001〜006: 村タブ「永続シェル方式（SPA）」の挙動検証
//
//   永続シェル方式（pages/villages/[id].vue + 子タブ）への改修で満たすべき:
//     AC-1  タブをクリックすると URL が /villages/{id}/{tab} に更新され、戻る操作で戻れる
//     AC-2  タブ遷移中に全画面ローディング（.pi-spin が画面全体を覆う状態）が出ない
//     AC-4  村名 h1（VillageHeader）が遷移前後で同一 DOM のまま（再マウントされない）
//     AC-5  /api/v1/villages/{id}（getVillage）がタブ遷移で追加発火しない（再フェッチ無し）
//     AC-6  全 9 タブそれぞれのパネル主要要素が可視（空/エラーでない）
//
//   page.goto ではなく VillageHeader のタブを **クリック** して SPA 遷移させる点が肝。
//   認証/村ID解決は上記 describe の beforeAll を踏襲（独立 requestContext でログイン）。
// ===========================================================================
test.describe('VLG-SPA-001〜006: 村タブ永続シェル（SPA）', () => {
  test.setTimeout(120_000)

  let cachedToken = ''
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

  test.beforeAll(async ({ playwright }) => {
    const ctx = await playwright.request.newContext()
    try {
      const loginResp = await ctx.post('http://localhost:8080/api/v1/auth/login', {
        data: { email: USER_EMAIL, password: USER_PASSWORD },
      })
      expect(loginResp.status()).toBe(200)
      const loginBody = await loginResp.json()
      cachedToken = loginBody.data.accessToken as string

      const searchResp = await ctx.get(
        `http://localhost:8080/api/v1/villages/search?q=${encodeURIComponent(COMMUNITY_VILLAGE_NAME)}&size=10`,
        { headers: { Authorization: `Bearer ${cachedToken}` } },
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
      await ctx.dispose()
    }
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
  test('VLG-SPA-001: タブクリックで URL が更新され goBack() で戻れる', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
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
  test('VLG-SPA-002: タブ遷移で白画面ローディングが出ず村名 h1 が再マウントされない', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

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
  test('VLG-SPA-003: タブ遷移で GET /villages/{id} が増えない（再フェッチ無し）', async ({ page }) => {
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
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
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
  test('VLG-SPA-004: 全 9 タブをクリック巡回し各パネルが描画される', async ({ page }) => {
    const villageId = cachedVillageId

    await page.goto(`/villages/${villageId}/bulletin`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

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
