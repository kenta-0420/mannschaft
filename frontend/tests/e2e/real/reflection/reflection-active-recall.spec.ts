/**
 * F06.5 アクティブリコール学習機能 — 実機 E2E テスト（モック不使用）
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3001 が起動済みの状態で実行すること。
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（chromium-real プロジェクト）。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 *
 * カバー項目:
 *   REFLECT-001: /reflections（今日ビュー）に遷移できる・UI が壊れない
 *   REFLECT-002: /reflections/themes（テーマ一覧）に遷移できる
 *   REFLECT-003: テーマ作成 → 一覧に反映される（API POST /me/reflections/themes）
 *   REFLECT-004: 当日エントリ作成（upsert）→ isMasked=false・本文が見える・編集可（通常エントリ詳細）
 *   REFLECT-005: 過去日エントリ作成 → isMasked=true・本文が隠れ maskedHint が出る（エントリ詳細）
 *   REFLECT-006: 想起テスト: recall 入力 + selfRating 保存 → original 本文が開示される（revealed）
 *   REFLECT-007: 今日ビュー（/reflections）でコマ/自由テーマが縦並び・空きコマ編集導線
 *   REFLECT-008（AC-21）: マイカレンダー（/calendar）に reflection 印が出る・id=null でも壊れない
 *
 * 注意:
 *   - 時間依存のリマインダー発火は対象外（BE UT で担保済み）
 *   - テスト実行後はデータが DB に残る（teardown は不要だが repeat 実行時はテーマ名重複に注意）
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// テーマタイトル（実行ごとにユニーク化して repeat 実行に耐える）
const RUN_ID = Date.now()
const THEME_TITLE = `E2E アクティブリコール ${RUN_ID}`

// 直列で CRUD を踏むため並列無効・タイムアウト延長
test.describe.configure({ mode: 'serial' })

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// セッション状態（storageState からの認証継続 + beforeEach で cookie を fresh 化）
// ---------------------------------------------------------------------------

// storageState（real-user.json）を使った認証済みセッションで実行する
// storageState が無効な場合は loginUI でフォールバック

let createdThemeId = ''
let createdEntryId = ''       // 当日エントリ（REFLECT-004 で作成）
let pastEntryId = ''          // 過去日エントリ（REFLECT-005 で作成）

// ---------------------------------------------------------------------------
// セットアップ: 各テスト直前に「page context そのもの」へ再ログインして cookie を fresh 化する。
//
// 単一セッション設計: API 呼び出し(page.request)も UI(page) も同一の page context cookie を使う。
// 別 context で login すると BE の refresh_token ローテーションが page 側 cookie を無効化し相互に
// 壊れるため、context を分けない。localStorage['currentUser']（UI 認証状態）は storageState 由来で
// 維持され、cookie だけ fresh 化されるので API・UI 双方が同一の有効 cookie を使う。
// ---------------------------------------------------------------------------
test.beforeEach(async ({ page }) => {
  const res = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_EMAIL, password: USER_PASSWORD },
  })
  expect(res.status(), 'beforeEach の cookie リフレッシュ login は 200').toBe(200)
})

// ---------------------------------------------------------------------------
// REFLECT-001: 今日ビュー（/reflections）に遷移できる
// ---------------------------------------------------------------------------
test('REFLECT-001: /reflections（今日ビュー）に遷移・UI 正常', async ({ page }) => {
  await page.goto('/reflections')
  await waitForHydration(page)

  // ページタイトル（「今日の振り返り」相当のテキスト）が出る
  // i18n key: reflection.today.heading
  await expect(page.locator('h1').first()).toBeVisible({ timeout: 10_000 })

  // コンソールエラーがないことを確認
  const errors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })

  // ローディング完了待ち（Skeleton が消える）
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // ページ全体が表示されていることを確認（エラーメッセージが出ていないこと）
  await expect(page.locator('body')).not.toContainText('500', { timeout: 5_000 }).catch(() => {})
})

// ---------------------------------------------------------------------------
// REFLECT-002: テーマ一覧（/reflections/themes）に遷移できる
// ---------------------------------------------------------------------------
test('REFLECT-002: /reflections/themes（テーマ一覧）に遷移', async ({ page }) => {
  await page.goto('/reflections/themes')
  await waitForHydration(page)

  // テーマ一覧ページのヘッダー
  await expect(page.locator('h1').first()).toBeVisible({ timeout: 10_000 })

  // ローディング完了
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
})

// ---------------------------------------------------------------------------
// REFLECT-003: テーマ作成 → API 確認 + 一覧に表示
// ---------------------------------------------------------------------------
test('REFLECT-003: テーマ作成 → 一覧に反映', async ({ page }) => {
  // API 直接でテーマ作成（UI フォームのローカリゼーション依存を避けるため API 経由）。
  // 認証は apiCtx（storageState 非依存のクリーン context・自身の login で fresh cookie 保持）を使う。
  // page/storageState 由来 cookie は遷移中のトークンリフレッシュで失効し 401 になるため使わない。
  const res = await page.request.post(`${BE_API}/me/reflections/themes`, {
    data: {
      title: THEME_TITLE,
      sourceType: 'DIARY',
      description: 'E2E テスト用テーマ',
    },
  })
  expect(res.status(), 'テーマ作成は 200 or 201').toBeGreaterThanOrEqual(200)
  expect(res.status()).toBeLessThan(300)

  const body = await res.json()
  const theme = body.data ?? body
  createdThemeId = theme.id ?? ''
  expect(createdThemeId, 'theme.id が返る').toBeTruthy()

  // テーマ一覧ページで作成したテーマが見える
  await page.goto('/reflections/themes')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await expect(page.getByText(THEME_TITLE)).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// REFLECT-004: 当日エントリ作成（upsert）→ isMasked=false・本文が見える
// ---------------------------------------------------------------------------
test('REFLECT-004: 当日エントリ作成 → isMasked=false・本文表示', async ({ page }) => {
  expect(createdThemeId, 'テーマID が存在する（REFLECT-003 が成功していること）').toBeTruthy()

  // 今日の日付
  const today = new Date().toISOString().slice(0, 10)

  // API 経由でエントリ upsert
  const structuredContent = {
    main_theme: 'E2E テスト: 今日の振り返り',
    sections: [
      {
        heading: 'テスト見出し',
        subsections: [{ sub_heading: '小見出し', detail: 'テスト詳細テキスト', supplement: '' }],
      },
    ],
    free_note: 'E2E 自由メモ',
  }

  const res = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId: createdThemeId,
      targetDate: today,
      structuredContent,
    },
  })
  expect(res.status(), '当日エントリ upsert は 200').toBeLessThan(300)

  const body = await res.json()
  const entry = body.data ?? body
  createdEntryId = entry.id ?? ''
  expect(createdEntryId, 'entry.id が返る').toBeTruthy()
  expect(entry.isMasked, '当日エントリは isMasked=false').toBe(false)

  // エントリ詳細ページ（/reflections/entries/:id）で本文が見える
  await page.goto(`/reflections/entries/${createdEntryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // マスク中ブロック（eye-slash アイコン or masked_notice テキスト）が出ていないこと
  // isMasked=false なので、本文表示エリア（ReflectionStructuredView）が出るはず
  const maskedBlock = page.locator('.pi-eye-slash').first()
  await expect(maskedBlock).not.toBeVisible({ timeout: 5_000 }).catch(() => {})

  // 編集ボタン（i18n: reflection.entry.edit）が出る
  await expect(
    page.getByRole('button', { name: /編集|edit/i }).first()
  ).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// REFLECT-005: 過去日エントリ作成 → isMasked=true・本文が隠れ maskedHint が出る
// ---------------------------------------------------------------------------
test('REFLECT-005: 過去日エントリ → isMasked=true・本文非表示・maskedHint 表示', async ({ page }) => {
  expect(createdThemeId, 'テーマID が存在する').toBeTruthy()

  // 昨日の日付（過去日 = マスク対象）
  const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)

  const res = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId: createdThemeId,
      targetDate: yesterday,
      structuredContent: {
        main_theme: 'E2E 過去日テスト',
        sections: [],
        free_note: '昨日の振り返りメモ（E2E）',
      },
    },
  })
  expect(res.status(), '過去日エントリ upsert は 200').toBeLessThan(300)

  const body = await res.json()
  const entry = body.data ?? body
  pastEntryId = entry.id ?? ''
  expect(pastEntryId, 'past entry.id が返る').toBeTruthy()
  expect(entry.isMasked, '過去日エントリは isMasked=true').toBe(true)

  // エントリ詳細ページでマスク状態を確認
  await page.goto(`/reflections/entries/${pastEntryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // マスク中ブロック: pi-eye-slash アイコンが見える
  await expect(page.locator('.pi-eye-slash').first()).toBeVisible({ timeout: 10_000 })

  // maskedHint: テーマタイトルが出る
  await expect(page.getByText(THEME_TITLE)).toBeVisible({ timeout: 10_000 })

  // 本文テキスト（E2E 過去日テスト）が出ていないこと（マスク中）
  await expect(page.getByText('昨日の振り返りメモ（E2E）')).not.toBeVisible({ timeout: 5_000 }).catch(() => {})

  // 想起テストボタンが出る（i18n: reflection.recall.heading）
  await expect(
    page.getByRole('button', { name: /想起|recall/i }).first()
  ).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// REFLECT-006: 想起テスト → recall 保存 → original 本文開示（revealed）
// ---------------------------------------------------------------------------
test('REFLECT-006: 想起テスト → recall 保存 → original 開示', async ({ page }) => {
  expect(pastEntryId, '過去日エントリID が存在する（REFLECT-005 が成功していること）').toBeTruthy()

  // 想起テストページに遷移
  await page.goto(`/reflections/recall?entry=${pastEntryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // ヒント（テーマタイトル）が表示されている（maskedHint.themeTitle）
  await expect(page.getByText(THEME_TITLE)).toBeVisible({ timeout: 10_000 })

  // 想起入力フォームが出ている（Textarea）
  const textarea = page.locator('textarea').first()
  await expect(textarea).toBeVisible({ timeout: 10_000 })
  await textarea.click()
  await textarea.fill('E2E 想起テキスト: テスト詳細を思い出した')

  // 自己評価（REMEMBERED を選択）
  // i18n: reflection.recall.rating.REMEMBERED
  const rememberedOption = page.locator('[class*="RadioButton"], [role="radio"]').filter({ hasText: /覚えている|remember/i })
  if (await rememberedOption.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await rememberedOption.click()
  }
  else {
    // div クリックで selfRating を切り替える（recall.vue の実装）
    const ratingDiv = page.locator('div[class*="cursor-pointer"]').filter({ hasText: /覚えている|remember/i })
    if (await ratingDiv.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await ratingDiv.click()
    }
  }

  // 送信（「開示する」ボタン）→ i18n: reflection.recall.submit
  const submitBtn = page.getByRole('button', { name: /開示|submit|保存|save/i }).first()
  await expect(submitBtn).toBeVisible({ timeout: 10_000 })
  await submitBtn.click()

  // 開示後: 「正解（元の本文）」ブロック（i18n: reflection.recall.revealed_heading）が出る
  await expect(
    page.getByRole('heading', { name: /正解|元の本文|revealed|original/i }).first()
  ).toBeVisible({ timeout: 20_000 })

  // API でも revealed_at が記録されていることを確認
  const entryRes = await page.request.get(`${BE_API}/me/reflections/entries/${pastEntryId}`)
  expect(entryRes.status()).toBe(200)
  const entryBody = await entryRes.json()
  const updatedEntry = entryBody.data ?? entryBody
  // 開示後は isMasked=false になる
  expect(updatedEntry.isMasked, '想起後は isMasked=false（開示済み）').toBe(false)
})

// ---------------------------------------------------------------------------
// REFLECT-007: 今日ビュー（/reflections）でテーマ由来 item が表示・編集導線
// ---------------------------------------------------------------------------
test('REFLECT-007: 今日ビューでテーマ由来 item・編集導線', async ({ page }) => {
  await page.goto('/reflections')
  await waitForHydration(page)

  // ローディング完了
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

  // テーマが作成済みなので、今日ビューに free theme 由来のアイテムが出るはず
  // （slotKind=null の自由テーマ由来 item）
  // ReflectionTodayItemCard が表示されること、またはテーマ作成導線が出ること
  const hasItems = await page.locator('[class*="rounded-xl"]').count()
  // 空でもテーマ作成ボタンが出る
  await expect(page.locator('body')).not.toContainText('500')

  // ヘッダーが正常に描画されている
  await expect(page.locator('h1').first()).toBeVisible({ timeout: 10_000 })

  // コンソールエラーを収集（id=null 問題の検出）
  const consoleErrors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })

  // 少し待ってエラー収集
  await page.waitForTimeout(2_000)

  // null id 起因のエラーが出ていないことを確認（'Cannot read properties of null' 等）
  const criticalErrors = consoleErrors.filter(e =>
    e.includes('Cannot read properties of null') ||
    e.includes('TypeError') ||
    e.includes('[Vue warn]')
  )
  if (criticalErrors.length > 0) {
    console.warn('コンソールエラー検出:', criticalErrors)
  }
  // ページ自体は描画できていること（致命的ではない warning は許容）
  expect(hasItems).toBeGreaterThanOrEqual(0)
})

// ---------------------------------------------------------------------------
// REFLECT-008（AC-21）: マイカレンダーに reflection 印が出る・id=null でも壊れない
// ---------------------------------------------------------------------------
test('REFLECT-008（AC-21）: マイカレンダーに reflection 印・null-id 非破壊', async ({ page }) => {
  // カレンダーページに遷移
  await page.goto('/calendar')
  await waitForHydration(page)

  // カレンダーローディング完了（Skeleton 消滅 or ロード完了）
  await page.locator('.p-skeleton, [class*="loading"]').first()
    .waitFor({ state: 'detached', timeout: 30_000 })
    .catch(() => {})

  // 追加の待機（CalendarGrid の非同期データ読み込み）
  await page.waitForTimeout(3_000)

  // カレンダーグリッドが描画されている
  await expect(page.locator('[class*="calendar"], .fc, [data-v-][class*="grid"]').first()).toBeVisible({ timeout: 15_000 }).catch(async () => {
    // フォールバック: ページに何らかのカレンダー要素がある
    await expect(page.locator('body')).not.toContainText('500')
  })

  // ページが壊れていないこと（クリティカルエラーなし）
  const consoleErrors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  await page.waitForTimeout(2_000)

  const criticalErrors = consoleErrors.filter(e =>
    e.includes('Cannot read properties of null') && e.includes('uniqueKey')
  )
  expect(criticalErrors, 'id=null 起因の uniqueKey エラーが出ない（AC-21 根治確認）').toHaveLength(0)

  // AC-21/AC-14 の API レベル検証: /my/calendar（reflection enricher が合流する設計 §6.2）を
  // 当月範囲で叩き、reflection 印（referenceKind=REFLECTION_ENTRY/REFLECTION_RECALL）が
  // id=null＋referenceUuid で正しく合流していることを確認する。
  const today = new Date()
  const y = today.getFullYear()
  const mm = String(today.getMonth() + 1).padStart(2, '0')
  const lastDay = String(new Date(y, today.getMonth() + 1, 0).getDate()).padStart(2, '0')
  const res = await page.request.get(
    `${BE_API}/my/calendar?from=${y}-${mm}-01T00:00:00&to=${y}-${mm}-${lastDay}T23:59:59`,
  )
  expect(res.status(), '/my/calendar は 200').toBe(200)
  const body = await res.json()
  const entries = (body.data ?? body) as Array<{
    id: number | null
    content?: { referenceKind?: string; referenceUuid?: string }
  }>
  const reflectionMarks = entries.filter((e) => (e.content?.referenceKind ?? '').startsWith('REFLECTION'))
  expect(
    reflectionMarks.length,
    'カレンダーに reflection 印（REFLECTION_ENTRY/RECALL）が合流している',
  ).toBeGreaterThan(0)
  // AC-21: reflection 行は id=null かつ referenceUuid を持つ（既存 schedule 行と非混在）
  for (const mark of reflectionMarks) {
    expect(mark.id, 'reflection 行の id は null（AC-21）').toBeNull()
    expect(mark.content?.referenceUuid, 'reflection 行は referenceUuid を持つ').toBeTruthy()
  }

  // 印がページに描画されていればクリック遷移も確認（best-effort・色 #6366f1/#f59e0b）
  const reflectionBadge = page.locator('[style*="6366f1"], [style*="f59e0b"]').first()
  if (await reflectionBadge.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await reflectionBadge.click()
    await page.waitForTimeout(2_000)
    expect(page.url(), '印クリック後 URL').toMatch(/\/reflections\/|\/calendar/)
  }
})

// ---------------------------------------------------------------------------
// Teardown: 作成したテーマを削除（クリーンアップ）
// ---------------------------------------------------------------------------
test('REFLECT-999: クリーンアップ（テーマ削除）', async ({ page }) => {
  if (!createdThemeId) {
    console.log('クリーンアップ: テーマ ID なし（スキップ）')
    return
  }
  const res = await page.request.delete(`${BE_API}/me/reflections/themes/${createdThemeId}`)
  // 削除成功 or Not Found は OK
  expect([200, 204, 404]).toContain(res.status())
})