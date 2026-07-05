/**
 * マッチング検索＋管理者CRUD一気通貫 実機E2E（#2118 FE / #2123 BE / #2125 BE / 認可根治 BE）
 *
 * ── 検証対象 ─────────────────────────────────────────────────────────────
 *   #2118 (FE): /matching ヘッダーを「マッチング」化、フィルタバー
 *     （都道府県→市区町村連動／カテゴリ／キーワード）、検索条件の自動記憶＋履歴チップ
 *   #2123 (BE): keyword 指定時も地域/カテゴリ等の全フィルタ条件を Service 経由で渡し、
 *     AND 絞り込みが効くよう根治（以前は keyword 指定時に他条件が無視されていた）
 *   #2125 (BE): match_requests.ft_mr_search を V139 で WITH PARSER ngram 化し、
 *     日本語の連続文字列（スペース区切りなし）でも 2 文字以上の部分一致検索が
 *     できるよう根治（標準パーサは日本語を1トークン化し部分一致が不発だった）
 *   認可根治 (BE): マッチング3コントローラ（募集/応募/NGチーム）の認可漏れ・IDOR・
 *     userID≠teamID 誤比較を一括根治。作成/編集/取り下げ/応募/NG登録＝管理者・副管理者のみ、
 *     自チーム募集一覧＝所属者のみ。編集/取り下げが「正当な管理者でも常時400」だった
 *     所有権誤比較を、募集チームの isAdminOrAbove 判定へ差し替え（MATCH-CRUD-001 で実証）。
 *
 * ── テスト戦略 ───────────────────────────────────────────────────────────
 *   1. e2e-user 保有チーム（＝当該チームの管理者）で API から検証用募集を4件作成する
 *      （東京×サッカー×中学生／大阪×サッカー×一般／東京×野球×一般／大阪×バスケ×中学生）
 *      これにより地域・カテゴリ・キーワードの単体/複合フィルタが可視的に判別できる。
 *   2. ブラウザで /matching のフィルタバーを実際に操作し、一覧カードの表示/非表示で検証する。
 *   3. 自動記憶・履歴チップは localStorage 永続化のため reload() で実機検証する。
 *   4. MATCH-CRUD-001 で「作成→詳細取得→編集(PUT)→取り下げ(DELETE)」の認証付き一気通貫を
 *      実 API で踏み、認可根治後に編集/削除が 200/204 で成功する（旧400にならない）ことを実証する。
 *
 * テストID: MATCH-SEARCH-001〜007 / MATCH-CRUD-001
 */

import { test, expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { execSync } from 'node:child_process'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import { selectDropdown } from '../helpers/form'

// 書き込み経路（募集作成）を含むため storageState に依存しない
test.use({ storageState: { cookies: [], origins: [] } })

const BE = process.env.BE_ORIGIN ?? process.env.API_BASE_URL ?? 'http://localhost:8081'
const BE_API = `${BE}/api/v1`
const API_BASE_URL = process.env.API_BASE_URL ?? BE

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// テスト全体を直列実行（フィクスチャ作成 → 各シナリオ → クリーンアップ）
test.describe.configure({ mode: 'serial' })

const RUN_SUFFIX = Date.now().toString().slice(-6)
const TITLE = {
  tokyoSoccerJH: `E2E検証_週末サッカー練習試合_東京_${RUN_SUFFIX}`,
  osakaSoccerAdult: `E2E検証_サッカー交流会_大阪_${RUN_SUFFIX}`,
  tokyoBaseballAdult: `E2E検証_野球大会のお知らせ_東京_${RUN_SUFFIX}`,
  osakaBasketballJH: `E2E検証_バスケ交流戦_大阪_${RUN_SUFFIX}`,
  crud: `E2E検証_CRUD一気通貫_${RUN_SUFFIX}`,
  crudEdited: `E2E検証_CRUD一気通貫_編集後_${RUN_SUFFIX}`,
}

interface LoginResult {
  accessToken: string
  userId: number
}

async function login(api: APIRequestContext): Promise<LoginResult> {
  const res = await api.post(`${BE_API}/auth/login`, { data: { email: USER_EMAIL, password: USER_PASSWORD } })
  expect(res.status(), 'ログインは 200').toBe(200)
  const json = (await res.json()) as { data: { accessToken: string; userId: number } }
  return { accessToken: json.data.accessToken, userId: json.data.userId }
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' }
}

async function resolveTeamId(api: APIRequestContext, token: string): Promise<string> {
  const res = await api.get(`${BE_API}/me/teams`, { headers: { Authorization: `Bearer ${token}` } })
  expect(res.status(), '/me/teams は 200').toBe(200)
  const json = (await res.json()) as { data: Array<{ id: number; name: string }> }
  const team = json.data.find((t) => t.name.includes('FC Tokyo U-18 Test')) ?? json.data[0]
  expect(team, 'マッチング募集作成に使うチームが存在する').toBeTruthy()
  return String(team!.id)
}

async function createMatchRequest(
  api: APIRequestContext,
  token: string,
  teamId: string,
  body: {
    title: string
    activityType: string
    activityDetail: string
    category: string
    prefectureCode: string
    cityCode?: string
  },
): Promise<number> {
  const res = await api.post(`${BE_API}/teams/${teamId}/matching/requests`, {
    headers: authHeaders(token),
    data: { ...body, visibility: 'PLATFORM' },
  })
  expect(res.status(), `募集作成(${body.title})は 201`).toBe(201)
  const json = (await res.json()) as { data: { id: number; status: string } }
  expect(json.data.status, '作成直後は OPEN').toBe('OPEN')
  return json.data.id
}

let api: APIRequestContext
let token: string
let teamId: string
const createdIds: number[] = []

test.beforeAll(async () => {
  api = await pwRequest.newContext()
  const result = await login(api)
  token = result.accessToken
  teamId = await resolveTeamId(api, token)

  createdIds.push(
    await createMatchRequest(api, token, teamId, {
      title: TITLE.tokyoSoccerJH,
      activityType: 'PRACTICE',
      activityDetail: 'サッカーの練習試合相手を募集',
      category: 'JUNIOR_HIGH',
      prefectureCode: '13',
      cityCode: '13101',
    }),
  )
  createdIds.push(
    await createMatchRequest(api, token, teamId, {
      title: TITLE.osakaSoccerAdult,
      activityType: 'EXCHANGE',
      activityDetail: '社会人サッカーチーム交流会',
      category: 'ADULT',
      prefectureCode: '27',
    }),
  )
  createdIds.push(
    await createMatchRequest(api, token, teamId, {
      title: TITLE.tokyoBaseballAdult,
      activityType: 'COMPETITION',
      activityDetail: '草野球大会参加者募集',
      category: 'ADULT',
      prefectureCode: '13',
      cityCode: '13101',
    }),
  )
  createdIds.push(
    await createMatchRequest(api, token, teamId, {
      title: TITLE.osakaBasketballJH,
      activityType: 'EXCHANGE',
      activityDetail: '中学生バスケ交流試合',
      category: 'JUNIOR_HIGH',
      prefectureCode: '27',
    }),
  )
})

test.afterAll(async () => {
  // 認可根治後、DELETE /api/v1/matching/requests/{id} は募集チーム管理者なら 200系で成功する
  // （旧: SecurityUtils.getCurrentUserId()=userID を teamId として entity.getTeamId() と誤比較し
  //  正当な管理者でも 400 INSUFFICIENT_PERMISSION → 本 PR で isAdminOrAbove 判定へ根治済み）。
  // API 削除がネットワーク等で失敗した場合に備え、MySQL 直接削除もベストエフォートで併用する。
  for (const id of createdIds) {
    await api.delete(`${BE_API}/matching/requests/${id}`, { headers: authHeaders(token) }).catch(() => {})
  }
  try {
    execSync(
      `wsl.exe -e docker exec mannschaft-mysql mysql -umannschaft -pmannschaft mannschaft -e "DELETE FROM match_requests WHERE title LIKE '%${RUN_SUFFIX}'"`,
    )
  } catch { /* ベストエフォート cleanup（DB 直接削除が失敗しても以降の処理は継続） */ }
  await api.dispose()
})

/** 各テストの直前で fresh にブラウザログインする（単一セッション設計・トークンローテ401回避） */
async function freshLogin(page: Page): Promise<void> {
  await page.context().clearCookies()
  await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })
}

async function gotoMatching(page: Page): Promise<void> {
  await page.goto('/matching')
  await waitForHydration(page)
  await expect(page.getByTestId('matching-filter-bar')).toBeVisible({ timeout: 20_000 })
}

// ──────────────────────────────────────────────────────────────────────────
// MATCH-CRUD-001: 認可根治の一気通貫（作成→詳細→編集(PUT)→取り下げ(DELETE)）
//   旧不具合: 編集/取り下げが「正当な管理者でも常時 400 INSUFFICIENT_PERMISSION」だった
//   （userID を teamId として誤比較）。根治後は 200/204 で成功することを実 API で実証する。
//   real spec は CI スモーク対象外のため、実走裏取りは殿が別途行う（コミットのみ）。
// ──────────────────────────────────────────────────────────────────────────
test('MATCH-CRUD-001: 募集の作成→詳細→編集→取り下げが認可根治後に成功する（旧400の根治確認）', async () => {
  // 1) 作成（管理者のみ許可・201）
  const createRes = await api.post(`${BE_API}/teams/${teamId}/matching/requests`, {
    headers: authHeaders(token),
    data: {
      title: TITLE.crud,
      activityType: 'PRACTICE',
      activityDetail: 'CRUD一気通貫の検証用募集',
      category: 'ANY',
      prefectureCode: '13',
      cityCode: '13101',
      visibility: 'PLATFORM',
    },
  })
  expect(createRes.status(), '作成は 201').toBe(201)
  const created = (await createRes.json()) as { data: { id: number; status: string } }
  const id = created.data.id
  createdIds.push(id)

  // 2) 詳細取得（200・作成内容が反映）
  const getRes = await api.get(`${BE_API}/matching/requests/${id}`, { headers: authHeaders(token) })
  expect(getRes.status(), '詳細取得は 200').toBe(200)
  const detail = (await getRes.json()) as { data: { content: { title: string } } }
  expect(detail.data.content.title, '作成したタイトルが取得できる').toBe(TITLE.crud)

  // 3) 編集（PUT・管理者の所有権チェック通過で 200。旧実装では userID≠teamID で常時400だった）
  const updateRes = await api.put(`${BE_API}/matching/requests/${id}`, {
    headers: authHeaders(token),
    data: {
      title: TITLE.crudEdited,
      activityType: 'PRACTICE',
      activityDetail: 'CRUD一気通貫の検証用募集（編集後）',
      category: 'ANY',
      prefectureCode: '13',
      cityCode: '13101',
      visibility: 'PLATFORM',
    },
  })
  expect(updateRes.status(), '編集は 200（旧400の根治確認）').toBe(200)
  const updated = (await updateRes.json()) as { data: { content: { title: string } } }
  expect(updated.data.content.title, '編集後タイトルが反映される').toBe(TITLE.crudEdited)

  // 4) 取り下げ（DELETE・204。旧実装では正当な管理者でも400だった）
  const deleteRes = await api.delete(`${BE_API}/matching/requests/${id}`, { headers: authHeaders(token) })
  expect(deleteRes.status(), '取り下げは 204（旧400の根治確認）').toBe(204)

  // 5) 取り下げ後は詳細取得で 404（論理削除で見えない）
  const afterDeleteRes = await api.get(`${BE_API}/matching/requests/${id}`, { headers: authHeaders(token) })
  expect(afterDeleteRes.status(), '取り下げ後の詳細取得は 404').toBe(404)
})

test.describe('検索フィルタ（ブラウザ操作）', () => {
  test.beforeEach(async ({ page }) => {
    await freshLogin(page)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-001: ヘッダーが「マッチング」表示（旧「対戦・交流を探す」ではない）
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-001: /matching ヘッダーが「マッチング」と表示される', async ({ page }) => {
    await page.goto('/matching')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マッチング', exact: true })).toBeVisible({ timeout: 20_000 })
    await expect(page.getByRole('heading', { name: '対戦・交流を探す' })).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-002: フィルタバーの表示・都道府県→市区町村の連動
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-002: フィルタバー（都道府県/市区町村/カテゴリ/キーワード/検索/クリア）が表示・操作できる', async ({ page }) => {
    await gotoMatching(page)

    const prefSelect = page.getByTestId('matching-prefecture-select')
    const citySelect = page.getByTestId('matching-city-select')
    const categorySelect = page.getByTestId('matching-category-select')
    const keywordInput = page.getByTestId('matching-keyword-input')
    const searchButton = page.getByTestId('matching-search-button')
    const clearButton = page.getByTestId('matching-clear-button')

    await expect(prefSelect).toBeVisible()
    await expect(citySelect).toBeVisible()
    await expect(categorySelect).toBeVisible()
    await expect(keywordInput).toBeVisible()
    await expect(searchButton).toBeVisible()
    await expect(clearButton).toBeVisible()

    // 都道府県未選択時は市区町村が disabled
    // PrimeVue Select は disabled 属性でなく p-disabled クラス（+ data-p="disabled"）で無効状態を表す
    await expect(citySelect).toHaveClass(/p-disabled/)

    // 都道府県「東京都」選択 → 市区町村が有効化され連動して選択肢が読み込まれる
    await selectDropdown(page, prefSelect, '東京都')
    await expect(citySelect).not.toHaveClass(/p-disabled/, { timeout: 10_000 })
    await selectDropdown(page, citySelect, '千代田区')
    await expect(citySelect).toContainText('千代田区')
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-003: 都道府県で絞ると一覧が実際に絞り込まれる（#2123 地域フィルタ根治確認）
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-003: 都道府県（東京都）で絞り込むと他県の募集が一覧から消える', async ({ page }) => {
    await gotoMatching(page)

    await selectDropdown(page, page.getByTestId('matching-prefecture-select'), '東京都')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.tokyoBaseballAdult, { exact: true })).toBeVisible()
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toHaveCount(0)
    await expect(page.getByText(TITLE.osakaBasketballJH, { exact: true })).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-004: カテゴリー絞り込み／都道府県＋カテゴリの複合AND
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-004: 都道府県＋カテゴリーの複合ANDで該当1件のみに絞られる', async ({ page }) => {
    await gotoMatching(page)

    await selectDropdown(page, page.getByTestId('matching-prefecture-select'), '東京都')
    await selectDropdown(page, page.getByTestId('matching-category-select'), '中学生')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    // 東京×中学生 に一致するのは tokyoSoccerJH のみ（tokyoBaseballAdult は同県だが ADULT なので除外）
    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.tokyoBaseballAdult, { exact: true })).toHaveCount(0)
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toHaveCount(0)
    await expect(page.getByText(TITLE.osakaBasketballJH, { exact: true })).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-005: 日本語キーワード「サッカー」で部分一致ヒット（#2125 ngram根治確認）
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-005: キーワード「サッカー」(2文字)で日本語部分一致検索がヒットする', async ({ page }) => {
    await gotoMatching(page)

    await page.getByTestId('matching-keyword-input').fill('サッカー')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    // 標準FULLTEXTパーサ(ngram化以前)では「サッカー」のような連続日本語で部分一致が不発だった。
    // ngram化後は両方の「サッカー」募集がヒットする。
    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toBeVisible()
    await expect(page.getByText(TITLE.tokyoBaseballAdult, { exact: true })).toHaveCount(0)
    await expect(page.getByText(TITLE.osakaBasketballJH, { exact: true })).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-006: キーワード＋地域／キーワード＋カテゴリの複合AND（#2123 根治確認）
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-006: キーワード指定時も地域/カテゴリのAND絞り込みが効く', async ({ page }) => {
    await gotoMatching(page)

    // キーワード「サッカー」＋ 都道府県「東京都」 → 東京の1件のみ
    await page.getByTestId('matching-keyword-input').fill('サッカー')
    await selectDropdown(page, page.getByTestId('matching-prefecture-select'), '東京都')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toHaveCount(0)

    // クリアしてから キーワード「サッカー」＋ カテゴリ「一般」 → 大阪の1件のみ
    await page.getByTestId('matching-clear-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    await page.getByTestId('matching-keyword-input').fill('サッカー')
    await selectDropdown(page, page.getByTestId('matching-category-select'), '一般')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // MATCH-SEARCH-007: 検索条件の自動記憶（再訪で復元）＋ 履歴チップでワンタップ再検索
  // ──────────────────────────────────────────────────────────────────────────
  test('MATCH-SEARCH-007: 検索条件が自動記憶され再訪で復元される。履歴チップで再検索できる', async ({ page }) => {
    await gotoMatching(page)

    // 検索1: 東京都のみ
    await selectDropdown(page, page.getByTestId('matching-prefecture-select'), '東京都')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    // 検索2: 大阪府のみ（直近条件として記憶される想定）
    await page.getByTestId('matching-clear-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')
    await selectDropdown(page, page.getByTestId('matching-prefecture-select'), '大阪府')
    await page.getByTestId('matching-search-button').click()
    await page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET')

    // 再訪（reload）→ 直近の検索条件（大阪府）がフィルタに復元され、結果も大阪のみになっている
    await page.reload()
    await waitForHydration(page)
    await expect(page.getByTestId('matching-filter-bar')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('matching-prefecture-select')).toContainText('大阪府', { timeout: 10_000 })
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toHaveCount(0)

    // 履歴チップが表示され、東京都での検索チップをクリックすると再検索される
    const tokyoChip = page.locator('[data-testid^="matching-history-chip-"]', { hasText: '東京都' }).first()
    await expect(tokyoChip).toBeVisible({ timeout: 10_000 })
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/v1/matching/requests') && r.request().method() === 'GET'),
      tokyoChip.click(),
    ])
    await expect(page.getByTestId('matching-prefecture-select')).toContainText('東京都', { timeout: 10_000 })
    await expect(page.getByText(TITLE.tokyoSoccerJH, { exact: true })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(TITLE.osakaSoccerAdult, { exact: true })).toHaveCount(0)
  })
})
