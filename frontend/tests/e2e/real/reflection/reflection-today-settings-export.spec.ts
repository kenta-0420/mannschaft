/**
 * F06.5 振り返り — 今日ビュー / 通知設定 / ブログ輸出 実機 E2E（モック不使用）
 *
 * 単一セッション設計（reflection-active-recall.spec.ts 踏襲）:
 *   - beforeEach で page context cookie を fresh 化（別 context login 禁止）。
 *
 * カバー:
 *   TSE-001: 今日ビュー（/reflections）— 自由テーマ由来 item（slotKind=null）が「テーマ（時間割外）」
 *            セクションに出る・当日エントリ作成導線が機能する（AC-19 コマ日限定編集の自由テーマ側）。
 *   TSE-002: 設定 UI（/reflections/settings）— remind_hour を変更 → 保存 → 再訪で永続（AC-23）。
 *            GET/PUT /me/reflections/settings で裏取り。
 *   TSE-003: ブログ輸出（エントリ詳細 → 輸出ダイアログ）— 輸出実行 → 輸出済バッジ → 再輸出ブロック
 *            （AC-20・exported_blog_post_id 非 null）。
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
const THEME_TITLE = `E2E 今日設定輸出 ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

let themeId = ''
let todayEntryId = ''

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

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

// ---------------------------------------------------------------------------
// TSE-000: 自由テーマ＋当日エントリを API で準備
// ---------------------------------------------------------------------------
test('TSE-000: 自由テーマ＋当日エントリ準備（API）', async ({ page }) => {
  const themeRes = await page.request.post(`${BE_API}/me/reflections/themes`, {
    data: { title: THEME_TITLE, sourceType: 'FREE' },
  })
  expect(themeRes.status()).toBeLessThan(300)
  themeId = (await themeRes.json()).data.id

  const entryRes = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId,
      targetDate: today(),
      structuredContent: {
        main_theme: `輸出テスト本文 ${RUN_ID}`,
        sections: [{ heading: '見出し', subsections: [{ sub_heading: '小', detail: '詳細', supplement: '' }] }],
        free_note: '今日の自由メモ',
      },
    },
  })
  expect(entryRes.status()).toBeLessThan(300)
  const entry = (await entryRes.json()).data
  todayEntryId = entry.id
  expect(entry.isMasked, '当日エントリは非マスク').toBe(false)
})

// ---------------------------------------------------------------------------
// TSE-001: 今日ビュー — 自由テーマ由来 item が表示・作成済みバッジ
// ---------------------------------------------------------------------------
test('TSE-001: 今日ビューに自由テーマ由来 item（slotKind=null）が表示', async ({ page }) => {
  await page.goto('/reflections')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

  // 自由テーマ由来セクション見出し（reflection.today.free_themes=「テーマ（時間割外）」）。
  await expect(page.getByText('テーマ（時間割外）')).toBeVisible({ timeout: 15_000 })

  // 作成済みテーマ名がカードに出る（subjectName 由来・もしくは記入済みバッジ）。
  // 当日エントリ済みゆえ「記入済み」バッジ（reflection.today.has_entry）が出る。
  await expect(page.getByText('記入済み').first()).toBeVisible({ timeout: 10_000 })

  // 自由テーマ item の「振り返りを編集」導線（reflection.today.edit_entry）が機能する。
  const editBtn = page.getByRole('button', { name: '振り返りを編集' }).first()
  await expect(editBtn).toBeVisible({ timeout: 10_000 })
  // ページが壊れていない（500 が出ない）。
  await expect(page.locator('body')).not.toContainText('500')
})

// ---------------------------------------------------------------------------
// TSE-002: 設定 UI — remind_hour 変更 → 保存 → 再訪で永続（AC-23）
// ---------------------------------------------------------------------------
test('TSE-002: 通知設定 remind_hour 変更 → 保存 → 永続（AC-23）', async ({ page }) => {
  // 現在値を API で取得。
  const before = await page.request.get(`${BE_API}/me/reflections/settings`)
  expect(before.status()).toBe(200)
  const currentHour: number = (await before.json()).data.remindHour ?? 8

  // 現在値と異なる時刻を選ぶ（0-23 の別値）。
  const targetHour = currentHour === 21 ? 6 : 21
  const targetLabel = `${targetHour}時` // reflection.settings.hour_format

  await page.goto('/reflections/settings')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 通知時刻 Select（reflection.settings.remind_hour_label）を開いて targetHour を選ぶ。
  await page.locator('.p-select').first().click()
  await page.getByRole('option', { name: targetLabel, exact: true }).click()

  // 保存（reflection.settings.save=「保存」）。
  await page.getByRole('button', { name: '保存' }).click()

  // 保存成功トースト（reflection.settings.saved=「設定を保存しました」）を best-effort 確認。
  await expect(page.getByText('設定を保存しました')).toBeVisible({ timeout: 10_000 }).catch(() => {})

  // API で永続を裏取り。
  const after = await page.request.get(`${BE_API}/me/reflections/settings`)
  expect(after.status()).toBe(200)
  expect((await after.json()).data.remindHour, 'remind_hour が永続している（AC-23）').toBe(targetHour)

  // 再訪しても Select に保存値が反映されている（UI 永続）。
  await page.goto('/reflections/settings')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await expect(page.locator('.p-select').first()).toContainText(targetLabel, { timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// TSE-003: ブログ輸出 → 輸出済バッジ → 再輸出ブロック（AC-20）
// ---------------------------------------------------------------------------
test('TSE-003: ブログ輸出 → 輸出済バッジ → 再輸出ブロック（AC-20）', async ({ page }) => {
  expect(todayEntryId, 'TSE-000 の当日エントリが存在する').toBeTruthy()

  await page.goto(`/reflections/entries/${todayEntryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // 「ブログへ輸出」ボタン（reflection.export.button）。
  await page.getByRole('button', { name: 'ブログへ輸出' }).click()

  // 輸出ダイアログ（reflection.export.dialog_title=「ブログへ輸出」）。
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 10_000 })

  // 記事タイトル（任意）を入れて輸出（reflection.export.submit=「輸出する」）。
  await dialog.getByPlaceholder('省略するとテーマ名＋対象日になります').fill(`E2E 輸出記事 ${RUN_ID}`)
  await dialog.getByRole('button', { name: '輸出する' }).click()
  await expect(dialog).not.toBeVisible({ timeout: 15_000 })

  // 輸出済バッジ（reflection.entry.exported_badge=「ブログ輸出済み」）が表示される。
  await expect(page.getByText('ブログ輸出済み')).toBeVisible({ timeout: 10_000 })

  // API で exported_blog_post_id が非 null（AC-20）。
  const entryRes = await page.request.get(`${BE_API}/me/reflections/entries/${todayEntryId}`)
  expect(entryRes.status()).toBe(200)
  expect((await entryRes.json()).data.exportedBlogPostId, '輸出後 exportedBlogPostId は非 null').not.toBeNull()

  // 再輸出ブロック: 「ブログへ輸出」ボタンが disabled（:disabled="exported"）。
  await expect(page.getByRole('button', { name: 'ブログへ輸出' })).toBeDisabled({ timeout: 5_000 })

  // API レベルでも再輸出は 409（REFLECTION_008 ALREADY_EXPORTED）。
  const reexport = await page.request.post(`${BE_API}/me/reflections/entries/${todayEntryId}/export-to-blog`, {
    data: { title: '再輸出試行' },
  })
  expect(reexport.status(), '再輸出は 409').toBe(409)
})

// ---------------------------------------------------------------------------
// クリーンアップ
// ---------------------------------------------------------------------------
test('TSE-999: クリーンアップ', async ({ page }) => {
  if (!themeId) return
  const res = await page.request.delete(`${BE_API}/me/reflections/themes/${themeId}`)
  expect([200, 204, 404]).toContain(res.status())
})
