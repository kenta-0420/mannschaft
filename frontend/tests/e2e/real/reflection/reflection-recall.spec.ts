/**
 * F06.5 振り返り — 想起テスト（recall）/ マスクの核 実機 E2E（モック不使用）
 *
 * 単一セッション設計（reflection-active-recall.spec.ts 踏襲）:
 *   - beforeEach で page context cookie を fresh 化（別 context login 禁止）。
 *   - 過去日エントリ（即マスク）を API で用意し、想起 UI を駆動する。
 *
 * カバー:
 *   RECALL-001: recall REMEMBERED → UI で開示（「正解（元の本文）」見出し＋original 本文）。
 *   RECALL-002: recall PARTIAL → 別エントリで開示。
 *   RECALL-003（AC-22）: recall FORGOT → 開示はされるが、API 再評価で isMasked=true（再マスク継続）。
 *   RECALL-004: マスク中エントリの直接編集 PUT は 409（recall 開示後のみ編集可）を page.request で確認。
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
const THEME_TITLE = `E2E 想起テーマ ${RUN_ID}`

// 過去日エントリの本文（マスク中は出ない・開示後に見える）。
const REM_DETAIL = `E2E REMEMBERED 詳細 ${RUN_ID}`
const PARTIAL_DETAIL = `E2E PARTIAL 詳細 ${RUN_ID}`
const FORGOT_DETAIL = `E2E FORGOT 詳細 ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

let themeId = ''
let remEntryId = ''
let partialEntryId = ''
let forgotEntryId = ''

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

/** 昨日（過去日 = 即マスク・interval 1 が到来済み）。 */
function yesterday(): string {
  return new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

/** マスク中エントリを 1 件 API で作る（昨日付け）。返り値 entryId。 */
async function createPastEntry(
  request: import('@playwright/test').APIRequestContext,
  detail: string,
): Promise<string> {
  const res = await request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId,
      targetDate: yesterday(),
      structuredContent: {
        main_theme: detail,
        sections: [
          { heading: '見出し', subsections: [{ sub_heading: '小見出し', detail, supplement: '' }] },
        ],
        free_note: '',
      },
    },
  })
  expect(res.status(), '過去日エントリ upsert は 2xx').toBeLessThan(300)
  const entry = (await res.json()).data
  expect(entry.isMasked, '過去日エントリは isMasked=true').toBe(true)
  return entry.id as string
}

// ---------------------------------------------------------------------------
// 前準備: テーマ作成（API）。過去日エントリは各テストで個別に作る（(theme,date)一意のため別テーマ不要だが
// 同一過去日に複数エントリを作れないので REMEMBERED/PARTIAL/FORGOT 用にテーマを分ける）。
// ---------------------------------------------------------------------------
test('RECALL-000: テーマ＋過去日エントリ準備（API）', async ({ page }) => {
  // REMEMBERED/PARTIAL/FORGOT はそれぞれ別テーマにして (theme, yesterday) 一意制約を回避する。
  const mk = async (suffix: string) => {
    const res = await page.request.post(`${BE_API}/me/reflections/themes`, {
      data: { title: `${THEME_TITLE} ${suffix}`, sourceType: 'FREE' },
    })
    expect(res.status()).toBeLessThan(300)
    return (await res.json()).data.id as string
  }

  themeId = await mk('REM')
  remEntryId = await createPastEntry(page.request, REM_DETAIL)

  themeId = await mk('PARTIAL')
  partialEntryId = await createPastEntry(page.request, PARTIAL_DETAIL)

  themeId = await mk('FORGOT')
  forgotEntryId = await createPastEntry(page.request, FORGOT_DETAIL)

  expect(remEntryId && partialEntryId && forgotEntryId).toBeTruthy()
})

/** 想起 UI を駆動して開示する共通フロー（rating ラベルで自己評価を選ぶ）。 */
async function runRecallUi(
  page: import('@playwright/test').Page,
  entryId: string,
  ratingLabel: '覚えていた' | '部分的' | '忘れていた',
  recallText: string,
): Promise<void> {
  await page.goto(`/reflections/recall?entry=${entryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // マスク中ゆえ本文（main_theme=detail）は出ていない。
  // 想起入力（reflection.recall.input_placeholder「覚えている範囲で書いてください」）。
  const textarea = page.getByPlaceholder('覚えている範囲で書いてください')
  await expect(textarea).toBeVisible({ timeout: 10_000 })
  await textarea.fill(recallText)

  // 自己評価（rating ラベルの行をクリック・recall.vue は div@click で selfRating を切替）。
  await page.getByText(ratingLabel, { exact: true }).first().click()

  // 送信（reflection.recall.submit=「保存して開示」）。
  await page.getByRole('button', { name: '保存して開示' }).click()

  // 開示後: 「正解（元の本文）」見出し（reflection.recall.revealed_heading）が出る。
  await expect(
    page.getByRole('heading', { name: '正解（元の本文）' }),
  ).toBeVisible({ timeout: 20_000 })
}

// ---------------------------------------------------------------------------
// RECALL-001: REMEMBERED → 開示（original 本文が見える）
// ---------------------------------------------------------------------------
test('RECALL-001: recall REMEMBERED → 開示・original 本文表示', async ({ page }) => {
  expect(remEntryId, 'RECALL-000 が成功していること').toBeTruthy()
  await runRecallUi(page, remEntryId, '覚えていた', 'E2E 想起: 覚えていた内容')

  // 開示ブロックに original の本文（REM_DETAIL）が見える。
  await expect(page.getByText(REM_DETAIL).first()).toBeVisible({ timeout: 10_000 })

  // API でも開示後は isMasked=false。
  const res = await page.request.get(`${BE_API}/me/reflections/entries/${remEntryId}`)
  expect(res.status()).toBe(200)
  expect((await res.json()).data.isMasked, 'REMEMBERED 後は非マスク（AC-7）').toBe(false)
})

// ---------------------------------------------------------------------------
// RECALL-002: PARTIAL → 開示（別エントリ）
// ---------------------------------------------------------------------------
test('RECALL-002: recall PARTIAL → 開示', async ({ page }) => {
  expect(partialEntryId).toBeTruthy()
  await runRecallUi(page, partialEntryId, '部分的', 'E2E 想起: 部分的に覚えていた')
  await expect(page.getByText(PARTIAL_DETAIL).first()).toBeVisible({ timeout: 10_000 })

  const res = await page.request.get(`${BE_API}/me/reflections/entries/${partialEntryId}`)
  expect((await res.json()).data.isMasked, 'PARTIAL 後は非マスク（AC-7）').toBe(false)
})

// ---------------------------------------------------------------------------
// RECALL-003（AC-22）: FORGOT → 開示はされるが、API 再評価で再マスク継続
// ---------------------------------------------------------------------------
test('RECALL-003（AC-22）: recall FORGOT → 開示後も isMasked=true（再マスク継続）', async ({ page }) => {
  expect(forgotEntryId).toBeTruthy()
  // UI で FORGOT 想起 → その場では original が開示される（recordRecall は常に revealed を返す）。
  await runRecallUi(page, forgotEntryId, '忘れていた', 'E2E 想起: 思い出せなかった')

  // ただし AC-22: FORGOT は翌想起で再マスク継続。GET で再評価すると isMasked=true のまま。
  const res = await page.request.get(`${BE_API}/me/reflections/entries/${forgotEntryId}`)
  expect(res.status()).toBe(200)
  const entry = (await res.json()).data
  expect(entry.isMasked, 'FORGOT 後は再評価で再マスク継続（AC-22）').toBe(true)
  // マスク中ゆえ structuredContent は null（本文は返らない）。
  expect(entry.structuredContent ?? null, 'マスク中は本文非開示').toBeNull()
})

// ---------------------------------------------------------------------------
// RECALL-004: マスク中エントリの直接編集 PUT は 409（recall 開示後のみ編集可）
// ---------------------------------------------------------------------------
test('RECALL-004: マスク中エントリの直接 PUT は 409（REFLECTION_ENTRY_MASKED）', async ({ page }) => {
  // FORGOT エントリは RECALL-003 後も isMasked=true（マスク中）。
  // version を取得して正しい expectedVersion を送る（version 不一致 409 と masked 409 を区別する）。
  const getRes = await page.request.get(`${BE_API}/me/reflections/entries/${forgotEntryId}`)
  expect(getRes.status()).toBe(200)
  const entry = (await getRes.json()).data
  expect(entry.isMasked, '前提: マスク中エントリ').toBe(true)
  // マスク中は structuredContent=null ゆえ themeId はメタから取得（マスク中でも themeId は開示される設計）。
  const tId = entry.themeId
  const tDate = entry.targetDate ?? entry.maskedHint?.targetDate ?? yesterday()

  const putRes = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId: tId,
      targetDate: tDate,
      structuredContent: { main_theme: '直接編集試行', sections: [], free_note: '' },
      expectedVersion: entry.version ?? 0,
    },
  })
  // マスク中の直接 PUT は 409（REFLECTION_006 ENTRY_MASKED）。
  expect(putRes.status(), 'マスク中エントリの直接 PUT は 409').toBe(409)
})

// ---------------------------------------------------------------------------
// クリーンアップ（作成テーマ 3 件削除）
// ---------------------------------------------------------------------------
test('RECALL-999: クリーンアップ', async ({ page }) => {
  const listRes = await page.request.get(`${BE_API}/me/reflections/themes`)
  const themes = ((await listRes.json()).data ?? []) as Array<{ id: string, title: string }>
  for (const t of themes.filter(x => x.title.startsWith(THEME_TITLE))) {
    const res = await page.request.delete(`${BE_API}/me/reflections/themes/${t.id}`)
    expect([200, 204, 404]).toContain(res.status())
  }
})
