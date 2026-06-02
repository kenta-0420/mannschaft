/**
 * F22.1 市（Market）Phase 1 — 実機 E2E（モック不使用）
 *
 * 実際のバックエンド（localhost:8080）とフロントエンド（dev server）に対して実行する。
 * page.route によるモックは一切使用しない。BE が返す camelCase JSON を FE が
 * undefined にせず実値描画できるか（PR #1221 の casing 修正 🔴-1/-2 の回帰確認）を主目的とする。
 *
 * 前提シードデータ（tests/e2e/real/market-seed.sql 投入済み・ID 90001〜90009）:
 *   90001 PUBLIC OPEN  別府市(44202) cat9   team1  confirmed 0/4
 *   90002 PUBLIC OPEN  大分市(44201) cat10  team2  confirmed 3/8
 *   90003 PUBLIC FULL  大分市(44201) cat9   team1  confirmed 2/2
 *   90004 PUBLIC OPEN  地域なし(null) cat10  team2  confirmed 5/20
 *   90005 FRIEND_TEAMS_ONLY OPEN  別府市 → 市に出ない・詳細 404
 *   90006 SCOPE_ONLY OPEN          → 市に出ない・詳細 404
 *   90007 PUBLIC CANCELLED         → 市に出ない・詳細 404
 *   90008 PUBLIC COMPLETED         → 市に出ない・詳細 404
 *   90009 PUBLIC OPEN (deleted_at) → 市に出ない・詳細 404
 *   created_by=90001（PII 保持ユーザー: email/姓名）→ 公開レスポンスに漏れないこと
 *
 * テストID:
 *   MR-001  未ログイン到達（一覧 GET 200・camelCase 実値描画）
 *   MR-002  カード実値（owner.displayName / region / 締切 / 定員 が undefined にならない）
 *   MR-003  詳細 GET 200・実値描画（owner 公称名表示）
 *   MR-004  PII 無し（メール・本名・createdBy・応募者一覧が画面に出ない）
 *   MR-005  404 存在秘匿（非公開/取消/完了/削除/不在は 404・403 混入なし）
 *   MR-006  県ロールアップ集計（summary byPrefecture=44→3 / byCity 44201→2 44202→1）
 *   MR-007  地域フィルタ（prefecture=44 で 4 件 / include_region_none=false で 3 件）
 *   MR-008  市から直接札を立てられない（post-link は /dashboard 導線のみ）
 *   MR-009  未ログイン /market が login へリダイレクトされない（#1225 redirect 根治の実証）
 */

import { test, expect, type APIResponse } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// 全テストを未認証で実行する（市は permitAll）
test.use({ storageState: { cookies: [], origins: [] } })

// 公開画面に出てはならない PII（シードユーザー 90001 由来）
const FORBIDDEN_PII = [
  'market-pii-leak@example.com',
  '漏洩太郎LastName',
  '漏洩太郎FirstName',
  '漏洩太郎',
] as const

// page.request（テスト直叩き）は実 BE オリジンを明示する（dev server proxy を経由しない）。
// 既定は :8080 直叩き。BASE_URL=3001（nitro proxy 経由）でも到達できるよう BE_ORIGIN で上書き可。
const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1/public/market`

// ──────────────────────────────────────────────────────────────────────────
// MR-001: 未ログイン到達 + camelCase 実値
// ──────────────────────────────────────────────────────────────────────────

test('MR-001: 未ログイン一覧 API が 200・camelCase 実値（owner.displayName / region / capacity / confirmedCount）を返す', async ({ page }) => {
  // 一覧 API を実 BE から直接取得し camelCase 実値を検証（🔴-1/-2 のデータ層回帰確認）。
  const listRes = await page.request.get(`${BE_API}/listings?size=50`)
  expect(listRes.status()).toBe(200)
  const json = (await listRes.json()) as {
    data: Array<{
      id: number
      title: string
      owner: { scopeType: string, scopeId: number, displayName: string, iconUrl: string | null }
      region: { prefectureCode: string, prefectureName: string, cityCode: string, cityName: string } | null
      category: { id: number, nameKey: string }
      confirmedCount: number
      capacity: number
      applicationDeadline: string
      status: string
    }>
    meta: { total: number, page: number, size: number, totalPages: number }
  }
  expect(json.meta.total).toBeGreaterThanOrEqual(4)
  const seeded = json.data.find((d) => d.id === 90001)!
  expect(seeded, 'シード札 90001 が一覧に含まれること').toBeTruthy()
  // camelCase キーが実値で存在する（snake_case 旧型なら undefined になっていた箇所）
  expect(seeded.owner.displayName).toBe('FC東京U-18（テスト）')
  expect(seeded.region!.prefectureName).toBe('大分県')
  expect(seeded.region!.cityName).toBe('別府市')
  expect(seeded.category.nameKey).toBe('recruitment.category.practice_match')
  expect(seeded.capacity).toBe(4)
  expect(seeded.confirmedCount).toBe(0)
  expect(seeded.applicationDeadline).toBeTruthy()
  // 非公開・取消・完了・削除札は一覧に出ない
  const ids = json.data.map((d) => d.id)
  for (const hidden of [90005, 90006, 90007, 90008, 90009]) {
    expect(ids).not.toContain(hidden)
  }
})

// ──────────────────────────────────────────────────────────────────────────
// MR-002: 詳細画面のブラウザ描画（casing 回帰の核心・実 BE→FE で undefined にならない）
// ──────────────────────────────────────────────────────────────────────────

test('MR-002: 詳細画面が実 BE データで owner公称名・タイトルを undefined にせず描画する（🔴-1/-2 回帰）', async ({ page }) => {
  await page.goto('/market/listings/90002')
  await waitForHydration(page)
  await expect(page.getByTestId('market-detail-card')).toBeVisible({ timeout: 15_000 })

  // owner.displayName（チーム公称名）が実値で描画される（casing バグなら undefined）
  await expect(page.getByTestId('market-detail-organizer-name')).toHaveText('FC東京U-15（テスト）')
  await expect(page.getByTestId('market-detail-title')).toContainText('フットサル大会')

  // 画面に 'undefined' / 'NaN' が描画されていないこと（casing 不一致の典型症状）
  const body = await page.locator('body').innerText()
  expect(body, '詳細画面に "undefined" が出てはならない').not.toContain('undefined')
  expect(body, '詳細画面に "NaN" が出てはならない').not.toContain('NaN')

  // 未ログインなので「ログインして応募」ボタン
  await expect(page.getByTestId('market-login-to-apply-btn')).toBeVisible()
})

// ──────────────────────────────────────────────────────────────────────────
// MR-003: 詳細 200・締切/定員の実値（カード相当の値が camelCase で取れる）
// ──────────────────────────────────────────────────────────────────────────

test('MR-003: 公開札詳細 API（90003 FULL）が camelCase 実値（capacity/confirmedCount/status）を返す', async ({ page }) => {
  const res = await page.request.get(`${BE_API}/listings/90003`)
  expect(res.status()).toBe(200)
  const json = (await res.json()) as {
    data: {
      id: number
      owner: { displayName: string }
      capacity: number
      confirmedCount: number
      status: string
      paymentEnabled: boolean
      region: { cityName: string } | null
    }
  }
  expect(json.data.owner.displayName).toBe('FC東京U-18（テスト）')
  expect(json.data.capacity).toBe(2)
  expect(json.data.confirmedCount).toBe(2)
  expect(json.data.status).toBe('FULL')
  expect(json.data.paymentEnabled).toBe(false)
  expect(json.data.region!.cityName).toBe('大分市')
})

// ──────────────────────────────────────────────────────────────────────────
// MR-004: PII 無し（実名ユーザー作成の札でも公開レスポンス／詳細画面に PII が出ない）
// ──────────────────────────────────────────────────────────────────────────

test('MR-004: 公開レスポンス・詳細画面に PII（メール・本名・createdBy・応募者）が一切出ない', async ({ page }) => {
  // BE 公開レスポンスに PII フィールドが存在しないこと（実 API 直叩き）
  const apiRes: APIResponse = await page.request.get(`${BE_API}/listings/90001`)
  expect(apiRes.status()).toBe(200)
  const raw = await apiRes.text()
  for (const key of ['createdBy', 'created_by', 'email', 'phone', 'firstName', 'lastName', 'applicants']) {
    expect(raw, `公開レスポンスに PII フィールド '${key}' が含まれてはならない`).not.toContain(key)
  }
  for (const pii of FORBIDDEN_PII) {
    expect(raw, `公開レスポンスに PII 値 '${pii}' が含まれてはならない`).not.toContain(pii)
  }

  // 詳細画面（実 BE 描画）に PII が出ないこと
  await page.goto('/market/listings/90001')
  await waitForHydration(page)
  await expect(page.getByTestId('market-detail-card')).toBeVisible({ timeout: 15_000 })
  const detailBody = await page.locator('body').innerText()
  for (const pii of FORBIDDEN_PII) {
    expect(detailBody, `詳細画面に PII '${pii}' が出てはならない`).not.toContain(pii)
  }
  expect(detailBody, '詳細画面にメール形式の文字列が出てはならない')
    .not.toMatch(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/)
})

// ──────────────────────────────────────────────────────────────────────────
// MR-005: 404 存在秘匿（403 混入なし）
// ──────────────────────────────────────────────────────────────────────────

test('MR-005: 非公開/取消/完了/削除/不在の札詳細は全て 404（403 が混入しない）', async ({ page }) => {
  // FRIEND_TEAMS_ONLY=90005 / SCOPE_ONLY=90006 / CANCELLED=90007 / COMPLETED=90008 / deleted=90009 / 不在=99999
  for (const id of [90005, 90006, 90007, 90008, 90009, 99999]) {
    const res = await page.request.get(`${BE_API}/listings/${id}`)
    expect(res.status(), `id=${id} は 404 で存在秘匿されること`).toBe(404)
    expect([401, 403]).not.toContain(res.status())
  }
  // 非公開札は一覧にも出ない（実 BE 応答で確認）
  const listRes = await page.request.get(`${BE_API}/listings?size=50`)
  const json = (await listRes.json()) as { data: Array<{ id: number }> }
  const ids = json.data.map((d) => d.id)
  for (const hidden of [90005, 90006, 90007, 90008, 90009]) {
    expect(ids, `id=${hidden} は公開一覧に含まれてはならない`).not.toContain(hidden)
  }

  // FE: 非公開札の直 URL は 404 ページ（詳細カードが描画されない）
  await page.goto('/market/listings/90005')
  await waitForHydration(page)
  await page.waitForTimeout(3_000)
  await expect(page.getByTestId('market-detail-card')).toHaveCount(0)
  const body = await page.locator('body').innerText()
  expect(body).toMatch(/404|見つかりません|Not Found/i)
})

// ──────────────────────────────────────────────────────────────────────────
// MR-006: 県ロールアップ集計
// ──────────────────────────────────────────────────────────────────────────

test('MR-006: summary が県ロールアップを正しく集計する（44→3 / 44201→2 / 44202→1）', async ({ page }) => {
  const res = await page.request.get(`${BE_API}/summary`)
  expect(res.status()).toBe(200)
  const json = (await res.json()) as {
    data: {
      byPrefecture: Array<{ code: string, name: string, count: number }>
      byCity: Array<{ code: string, name: string, count: number }>
    }
  }
  const pref44 = json.data.byPrefecture.find((p) => p.code === '44')
  expect(pref44, '都道府県 44 が集計に含まれること').toBeTruthy()
  // L1(44202)+L2(44201)+L3(44201)=3。L4(地域なし)は byPrefecture に含まれない
  expect(pref44!.count).toBe(3)
  expect(pref44!.name).toBe('大分県')

  const city44201 = json.data.byCity.find((c) => c.code === '44201')
  const city44202 = json.data.byCity.find((c) => c.code === '44202')
  expect(city44201!.count, '大分市(44201)=L2+L3=2').toBe(2)
  expect(city44202!.count, '別府市(44202)=L1=1').toBe(1)
})

// ──────────────────────────────────────────────────────────────────────────
// MR-007: 地域フィルタ + include_region_none トグル
// ──────────────────────────────────────────────────────────────────────────

test('MR-007: prefecture=44 ロールアップと include_region_none トグルが効く', async ({ page }) => {
  // prefecture=44（既定 include_region_none=true）→ L1/L2/L3 + 地域なし L4 = 4 件
  const withNone = await page.request.get(`${BE_API}/listings?prefecture=44&size=50`)
  const a = (await withNone.json()) as { data: Array<{ id: number }>, meta: { total: number } }
  const aIds = a.data.map((d) => d.id).sort()
  expect(aIds).toEqual([90001, 90002, 90003, 90004])

  // prefecture=44 & include_region_none=false → 地域なし L4 が落ちて 3 件
  const withoutNone = await page.request.get(`${BE_API}/listings?prefecture=44&include_region_none=false&size=50`)
  const b = (await withoutNone.json()) as { data: Array<{ id: number }>, meta: { total: number } }
  const bIds = b.data.map((d) => d.id).sort()
  expect(bIds).toEqual([90001, 90002, 90003])

  // city=44202（別府市）指定 + include_region_none=false → 別府市の L1 のみ。
  // 注: 既定（include_region_none=true）では region-none の OR 枝が city/prefecture 指定で
  //     ゲートされないため L4 も混入する（searchMarketListings JPQL の既知の挙動）。
  //     市区町村まで絞り込む UI 経路では include_region_none=false が妥当。
  const cityRes = await page.request.get(`${BE_API}/listings?prefecture=44&city=44202&include_region_none=false&size=50`)
  const c = (await cityRes.json()) as { data: Array<{ id: number }> }
  expect(c.data.map((d) => d.id)).toEqual([90001])

  // 既定（include_region_none=true）では region-none 札 L4 が city 指定にも混入することを記録
  const cityWithNone = await page.request.get(`${BE_API}/listings?prefecture=44&city=44202&size=50`)
  const cn = (await cityWithNone.json()) as { data: Array<{ id: number }> }
  expect(cn.data.map((d) => d.id).sort()).toEqual([90001, 90004])
})

// ──────────────────────────────────────────────────────────────────────────
// MR-008: 市から直接札を立てられない（導線のみ）
// ──────────────────────────────────────────────────────────────────────────

// PR #1225 で MR-009 の redirect バグが根治され、未ログインでも市一覧ページが描画される
//    ようになったため fixme を解除（実 BE #1225 に対して検証）。
test('MR-008: 市ページの「札を立てる」はダッシュボード導線のみ（市から直接フォームを開かない）', async ({ page }) => {
  await page.goto('/market')
  await waitForHydration(page)
  await expect(page.getByTestId('market-page')).toBeVisible({ timeout: 15_000 })

  // 市ページに札作成フォームは存在しない
  await expect(page.getByTestId('market-form-extension')).toHaveCount(0)

  // 「札を立てる」ボタンはダッシュボードへの導線
  const postBtn = page.getByTestId('market-post-link')
  await expect(postBtn).toBeVisible()
  await postBtn.click()
  await page.waitForURL(/\/dashboard/, { timeout: 10_000 })
})

// ──────────────────────────────────────────────────────────────────────────
// MR-009: 未ログイン /market が login へリダイレクトされない（#1225 redirect 根治の実証）
// ──────────────────────────────────────────────────────────────────────────
//
// 旧バグ（2026-05-31 実機 E2E で発見・PR #1225 で根治）:
//   公開の市一覧ページ pages/market/index.vue は onMounted でジャンルフィルタ用に認証必須の
//   GET /api/v1/recruitment-categories を直叩きしていた。未ログインだと 401 を返し、
//   useApi.onResponseError が user=null での 401 を navigateTo('/login') で処理するため、
//   市一覧ページごとログインへ飛ばされ、未ログイン来訪者に市が一切表示されなかった。
//
// 根治（PR #1225）:
//   - BE: GET /api/v1/public/market/categories を新設し permitAll 化
//   - FE: market/index.vue の fetchCategories() を公開エンドポイント経由に切替
//         （recruitment-categories 直叩きを廃止）
//
// 本テストは「未ログインで /market が login へ飛ばず市一覧が描画される」正挙動を固定し、
// redirect の消滅を実証する反転後の回帰テスト。
test('MR-009: 未ログイン /market が login へリダイレクトされず市一覧が描画される（#1225 根治の実証）', async ({ page }) => {
  // recruitment-categories（旧 401 経路）が呼ばれていないこと / 公開 categories が呼ばれることを記録
  const legacyCategoriesStatuses: number[] = []
  const publicCategoriesStatuses: number[] = []
  page.on('response', (r) => {
    const url = r.url()
    if (url.includes('/api/v1/public/market/categories')) {
      publicCategoriesStatuses.push(r.status())
    } else if (url.includes('/api/v1/recruitment-categories')) {
      legacyCategoriesStatuses.push(r.status())
    }
  })

  await page.goto('/market')
  await waitForHydration(page)

  // 市一覧ページが描画される（login へ飛ばない）
  await expect(page.getByTestId('market-page')).toBeVisible({ timeout: 15_000 })
  expect(page.url(), '未ログイン /market は login へリダイレクトされない（#1225 根治）').not.toContain('/login')

  // 旧 401 経路（recruitment-categories 直叩き）は使われていない
  expect(legacyCategoriesStatuses, '旧 recruitment-categories 直叩きは廃止されている').not.toContain(401)

  // 公開市 API・公開カテゴリ API が未ログインで 200 を返す（permitAll の実証）
  const listRes = await page.request.get(`${BE_API}/listings?size=5`)
  expect(listRes.status(), '市一覧 API は未ログインで 200').toBe(200)
  const catRes = await page.request.get(`${BE_API}/categories`)
  expect(catRes.status(), '公開カテゴリ API は未ログインで 200（permitAll）').toBe(200)
  const catJson = (await catRes.json()) as { data: Array<{ id: number, nameKey?: string }> }
  expect(Array.isArray(catJson.data), '公開カテゴリ API は data 配列を返す').toBe(true)
})
