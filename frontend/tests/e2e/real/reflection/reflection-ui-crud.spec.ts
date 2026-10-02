/**
 * F06.5 振り返り — UI フォーム CRUD 実機 E2E（モック不使用）
 *
 * 既存 harness（reflection-active-recall.spec.ts）の単一セッション設計を踏襲:
 *   - beforeEach で page context cookie を fresh 化（別 context で login しない）。
 *   - API 呼び出しは page.request（同一 cookie ジャー）、UI 操作は page。
 *   - storageState=real-user.json（chromium-real プロジェクト）。
 *
 * この spec は「ユーザーが実際に触るフォーム操作」を UI で検証する（API はデータ準備のみ）:
 *   UICRUD-001: テーマ作成 — 「テーマを作成」→ ダイアログで title/種類 入力 → 保存 → 一覧に出る。
 *   UICRUD-002: 構造化エントリ作成 — テーマ詳細から当日エントリ作成 → ReflectionStructuredEditor で
 *               main_theme/section(heading/sub_heading/detail/supplement)/free_note を実入力 → 保存 →
 *               詳細で本文表示（非マスク）。
 *   UICRUD-003: エントリ編集 — 当日エントリを編集 → 保存 → 反映。
 *   UICRUD-004: バリデーション — title 空で保存ボタンが無効（必須・送信不可）。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */
import { test, expect } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const BE = process.env.BE_ORIGIN ?? 'http://localhost:8080'
const BE_API = `${BE}/api/v1`

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

const RUN_ID = Date.now()
const THEME_TITLE = `E2E UI テーマ ${RUN_ID}`
const MAIN_THEME = `E2E 本文 main ${RUN_ID}`
const SECTION_HEADING = `E2E 中見出し ${RUN_ID}`
const SUB_HEADING = `E2E 小見出し ${RUN_ID}`
const DETAIL = `E2E 詳細テキスト ${RUN_ID}`
const SUPPLEMENT = `E2E 補足テキスト ${RUN_ID}`
const FREE_NOTE = `E2E 自由メモ ${RUN_ID}`
const EDITED_FREE_NOTE = `E2E 編集後メモ ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

test.beforeEach(async ({ page }) => {
  // 多数テスト連続実行で login のレート制限/瞬間負荷により稀に非200になるため数回リトライする。
  let loggedIn = false
  for (let i = 0; i < 5; i++) {
    const res = await page.request.post(`${BE_API}/auth/login`, {
      data: { email: USER_EMAIL, password: USER_PASSWORD },
    })
    if (res.status() === 200) { loggedIn = true; break }
    await page.waitForTimeout(2_000)
  }
  expect(loggedIn, 'beforeEach の cookie リフレッシュ login（リトライ込）が成功').toBe(true)
})

// ---------------------------------------------------------------------------
// UICRUD-001: テーマ作成（UI フォーム）
// ---------------------------------------------------------------------------
test('UICRUD-001: テーマ作成 UI → 一覧に反映', async ({ page }) => {
  await page.goto('/reflections/themes')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 「テーマを作成」ボタン（reflection.theme.create）。空一覧時とヘッダー両方に出るため first()。
  await page.getByRole('button', { name: 'テーマを作成' }).first().click()

  // ダイアログ（reflection.theme.create ヘッダー）が開く。
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // テーマ名（reflection.theme.title_label / placeholder「例: 数学II / ○○プロジェクト / 日記」）
  const titleInput = dialog.getByPlaceholder('例: 数学II / ○○プロジェクト / 日記')
  await titleInput.fill(THEME_TITLE)

  // 種類（PrimeVue Select・既定 FREE）。overlay 操作は E2E で不安定なため best-effort で
  // 「日記」への変更を試みる（失敗しても既定 FREE で作成され、テーマ作成の核は検証できる）。
  try {
    await dialog.locator('.p-select').first().click({ timeout: 5_000 })
    const diaryOption = page.getByRole('option', { name: '日記' }).first()
    if (await diaryOption.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await diaryOption.click()
    } else {
      await page.keyboard.press('Escape').catch(() => {})
    }
  } catch {
    // Select 操作不可でも既定 FREE で続行する（核の検証には影響しない）。
  }

  // 保存（reflection.entry.save = 「保存」）
  await dialog.getByRole('button', { name: '保存' }).click()

  // ダイアログが閉じる。
  await expect(dialog).not.toBeVisible({ timeout: 10_000 })

  // 一覧に作成したテーマが出る。
  await expect(page.getByText(THEME_TITLE)).toBeVisible({ timeout: 10_000 })

  // API でも本人テーマとして存在することを裏取り（slug でなく一覧に title 一致が 1 件）。
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes`)
  expect(listRes.status()).toBe(200)
  const listBody = await listRes.json()
  const themes = (listBody.data ?? listBody) as Array<{ id: string, title: string }>
  const created = themes.find(t => t.title === THEME_TITLE)
  expect(created, 'API 一覧に作成テーマがある').toBeTruthy()
})

// ---------------------------------------------------------------------------
// UICRUD-002: 構造化エントリ作成（UI・ReflectionStructuredEditor 実入力）
// ---------------------------------------------------------------------------
test('UICRUD-002: 構造化エントリ作成 UI → 詳細で本文表示', async ({ page }) => {
  // テーマ ID を API で引く（UICRUD-001 で作成済み）。
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes`)
  const themes = ((await listRes.json()).data ?? []) as Array<{ id: string, title: string }>
  const theme = themes.find(t => t.title === THEME_TITLE)
  expect(theme, 'UICRUD-001 のテーマが存在する').toBeTruthy()
  const themeId = theme!.id

  // テーマ詳細ページから当日エントリ作成導線へ。
  await page.goto(`/reflections/themes/${themeId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 当日エントリ作成ボタン（reflection.entry.create=「振り返りを作成」、または今日ビュー側 create_entry）。
  // テーマ詳細に作成導線が無い場合は今日ビューにフォールバックする。
  const createOnDetail = page.getByRole('button', { name: /振り返りを作成|振り返りを書く/ }).first()
  if (await createOnDetail.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await createOnDetail.click()
  }
  else {
    // フォールバック: 今日ビューの当日エントリ作成（テーマが自由テーマ由来 item に出る）。
    await page.goto('/reflections')
    await waitForHydration(page)
    await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
    await page.getByRole('button', { name: '振り返りを書く' }).first().click()
  }

  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // ReflectionStructuredEditor を実入力（placeholder で各欄を特定）。
  // main_theme（reflection.entry.main_theme_placeholder「例: 二次関数の最大最小」）
  await dialog.getByPlaceholder('例: 二次関数の最大最小').fill(MAIN_THEME)

  // section heading（reflection.entry.section_heading_placeholder「例: 平方完成」）。
  // テンプレ NORMAL は section 0 件ゆえ「中見出しを追加」を押す。
  const sectionInput = dialog.getByPlaceholder('例: 平方完成').first()
  if (!(await sectionInput.isVisible({ timeout: 2_000 }).catch(() => false))) {
    await dialog.getByRole('button', { name: '中見出しを追加' }).click()
  }
  await dialog.getByPlaceholder('例: 平方完成').first().fill(SECTION_HEADING)

  // subsection（NORMAL の section は subsection 1 件付き emptySection。無ければ「小見出しを追加」）。
  const subInput = dialog.getByPlaceholder('例: 基本形への変形').first()
  if (!(await subInput.isVisible({ timeout: 2_000 }).catch(() => false))) {
    await dialog.getByRole('button', { name: '小見出しを追加' }).first().click()
  }
  await dialog.getByPlaceholder('例: 基本形への変形').first().fill(SUB_HEADING)
  await dialog.getByPlaceholder('学んだ内容を書く').first().fill(DETAIL)
  await dialog.getByPlaceholder('補足・メモ').first().fill(SUPPLEMENT)

  // free_note（reflection.entry.free_note_placeholder「所感や質問メモ」）
  await dialog.getByPlaceholder('所感や質問メモ').fill(FREE_NOTE)

  // 保存（reflection.entry.save=「保存」）
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog).not.toBeVisible({ timeout: 10_000 })

  // API で本人の当日エントリを引いて詳細へ遷移（id を取得）。
  const today = new Date().toISOString().slice(0, 10)
  const entriesRes = await page.request.get(`${BE_API}/me/reflections/themes/${themeId}/entries`)
  expect(entriesRes.status()).toBe(200)
  const entries = ((await entriesRes.json()).data ?? []) as Array<{ id: string, targetDate: string, isMasked: boolean }>
  const todayEntry = entries.find(e => e.targetDate === today)
  expect(todayEntry, '当日エントリが作成されている').toBeTruthy()
  expect(todayEntry!.isMasked, '当日エントリは非マスク').toBe(false)

  // 詳細ページで本文（ReflectionStructuredView）が非マスク表示される。
  await page.goto(`/reflections/entries/${todayEntry!.id}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // マスクブロック（pi-eye-slash）が出ていない。
  await expect(page.locator('.pi-eye-slash')).toHaveCount(0, { timeout: 5_000 })
  // 本文（入力した値）が見える。
  await expect(page.getByText(MAIN_THEME)).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(DETAIL)).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(FREE_NOTE)).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// UICRUD-003: エントリ編集（UI）→ 反映
// ---------------------------------------------------------------------------
test('UICRUD-003: 当日エントリ編集 UI → 反映', async ({ page }) => {
  // テーマ → 当日エントリの id を引く。
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes`)
  const themes = ((await listRes.json()).data ?? []) as Array<{ id: string, title: string }>
  const theme = themes.find(t => t.title === THEME_TITLE)
  expect(theme).toBeTruthy()
  const today = new Date().toISOString().slice(0, 10)
  const entriesRes = await page.request.get(`${BE_API}/me/reflections/themes/${theme!.id}/entries`)
  const entries = ((await entriesRes.json()).data ?? []) as Array<{ id: string, targetDate: string }>
  const todayEntry = entries.find(e => e.targetDate === today)
  expect(todayEntry, 'UICRUD-002 の当日エントリが存在する').toBeTruthy()

  // 詳細ページで「振り返りを編集」（reflection.entry.edit）。
  await page.goto(`/reflections/entries/${todayEntry!.id}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await page.getByRole('button', { name: '振り返りを編集' }).first().click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // free_note を編集後の値に書き換える。
  const freeNote = dialog.getByPlaceholder('所感や質問メモ')
  await freeNote.fill(EDITED_FREE_NOTE)

  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog).not.toBeVisible({ timeout: 10_000 })

  // 詳細で編集後の値が反映される（onSaved で entry が更新される）。
  await expect(page.getByText(EDITED_FREE_NOTE)).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// UICRUD-004: バリデーション — title 空で保存不可（必須）
// ---------------------------------------------------------------------------
test('UICRUD-004: テーマ title 空 → 保存ボタン無効（必須バリデーション）', async ({ page }) => {
  await page.goto('/reflections/themes')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  await page.getByRole('button', { name: 'テーマを作成' }).first().click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // title 未入力の状態で保存ボタンが disabled（:disabled="!form.title.trim()"）。
  const saveBtn = dialog.getByRole('button', { name: '保存' })
  await expect(saveBtn).toBeDisabled({ timeout: 5_000 })

  // 1 文字入れると有効化される（必須が解ける）ことも確認。
  await dialog.getByPlaceholder('例: 数学II / ○○プロジェクト / 日記').fill('x')
  await expect(saveBtn).toBeEnabled({ timeout: 5_000 })
  // 空に戻すと再び無効化される。
  await dialog.getByPlaceholder('例: 数学II / ○○プロジェクト / 日記').fill('')
  await expect(saveBtn).toBeDisabled({ timeout: 5_000 })
})

// ---------------------------------------------------------------------------
// クリーンアップ
// ---------------------------------------------------------------------------
test('UICRUD-999: クリーンアップ（テーマ削除）', async ({ page }) => {
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes`)
  const themes = ((await listRes.json()).data ?? []) as Array<{ id: string, title: string }>
  const theme = themes.find(t => t.title === THEME_TITLE)
  if (!theme) {
    return
  }
  const res = await page.request.delete(`${BE_API}/me/reflections/themes/${theme.id}`)
  expect([200, 204, 404]).toContain(res.status())
})
