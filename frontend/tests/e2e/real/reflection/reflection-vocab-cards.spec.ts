/**
 * F06.5 アクティブリコール Phase 4 § 13 — 暗記カード（TERM_CARD）実機 E2E テスト
 *
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3001 が起動済みの状態で実行すること。
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用（chromium-real プロジェクト）。
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 *
 * カバー項目:
 *   VOCAB-001（AC-46）: TERM_CARD エントリを API 作成 → structured_content が DB に永続化される
 *   VOCAB-002（AC-47）: EP #23 で TERM_CARD カードが期間横断で取得できる
 *   VOCAB-003（AC-51）: マスク中エントリの maskedHint.cardQuiz に answer 側が漏れない（fail-closed）
 *   VOCAB-004（AC-59）: EP #23 呼び出しは recall_attempts を書き込まない
 *   VOCAB-005（AC-61）: 期間内に TERM_CARD がなければ totalCards=0・cards=[] を返す
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

const RUN_ID = Date.now()
const THEME_TITLE = `E2E 暗記カード ${RUN_ID}`

// TERM_CARD テスト用データ（ユニーク化）
const TERM1 = `apple-${RUN_ID}`
const MEANING1 = `りんご-${RUN_ID}`
const TERM2 = `banana-${RUN_ID}`
const MEANING2 = `バナナ-${RUN_ID}`

// 直列実行（CRUD 依存）
test.describe.configure({ mode: 'serial' })

// ---------------------------------------------------------------------------
// 状態変数（テスト間で共有）
// ---------------------------------------------------------------------------
let themeId = ''
let currentEntryId = ''  // 当日エントリ（AC-46/47 用）
let pastEntryId = ''     // 過去日エントリ（AC-51 用）

// ---------------------------------------------------------------------------
// セットアップ: 各テスト前に cookie fresh 化（単一セッション設計）
// ---------------------------------------------------------------------------
test.beforeEach(async ({ page }) => {
  const res = await page.request.post(`${BE_API}/auth/login`, {
    data: { email: USER_EMAIL, password: USER_PASSWORD },
  })
  expect(res.status(), 'beforeEach cookie リフレッシュ login は 200').toBe(200)
})

// ---------------------------------------------------------------------------
// VOCAB-001（AC-46）: TERM_CARD エントリを API 作成 → structured_content が DB に永続化
// ---------------------------------------------------------------------------
test('VOCAB-001（AC-46）: TERM_CARD エントリ作成 → cards.term/meaning が永続化', async ({ page }) => {
  // 1. テーマ作成
  const themeRes = await page.request.post(`${BE_API}/me/reflections/themes`, {
    data: {
      title: THEME_TITLE,
      sourceType: 'DIARY',
      description: 'E2E 暗記カードテスト用テーマ',
    },
  })
  expect(themeRes.status(), 'テーマ作成は 2xx').toBeLessThan(300)
  const themeBody = await themeRes.json()
  const theme = themeBody.data ?? themeBody
  themeId = theme.id ?? ''
  expect(themeId, 'theme.id が返る').toBeTruthy()

  // 2. 当日 TERM_CARD エントリ作成（今日 → isMasked=false）
  const today = new Date().toISOString().slice(0, 10)
  const structuredContent = {
    main_theme: 'E2E 暗記カードテスト',
    sections: [
      {
        type: 'TERM_CARD',
        heading: '英単語セクション',
        subsections: [],
        cards: [
          { term: TERM1, meaning: MEANING1 },
          { term: TERM2, meaning: MEANING2 },
        ],
      },
    ],
    free_note: '',
  }

  const entryRes = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: { themeId, targetDate: today, structuredContent },
  })
  expect(entryRes.status(), '当日エントリ upsert は 2xx').toBeLessThan(300)

  const entryBody = await entryRes.json()
  const entry = entryBody.data ?? entryBody
  currentEntryId = entry.id ?? ''
  expect(currentEntryId, 'entry.id が返る').toBeTruthy()
  expect(entry.isMasked, '当日エントリは isMasked=false').toBe(false)

  // 3. エントリを再取得 → TERM_CARD と cards が正しく永続化されているか確認
  const getRes = await page.request.get(`${BE_API}/me/reflections/entries/${currentEntryId}`)
  expect(getRes.status()).toBe(200)
  const getBody = await getRes.json()
  const fetched = getBody.data ?? getBody

  expect(fetched.isMasked, 're-fetch: isMasked=false').toBe(false)
  expect(fetched.structuredContent, 'structuredContent が返る').toBeTruthy()

  const sections = fetched.structuredContent?.sections ?? []
  expect(sections.length, 'セクションが1件').toBe(1)
  expect(sections[0].type, 'TERM_CARD type が保存されている（AC-46）').toBe('TERM_CARD')

  const cards = sections[0].cards ?? []
  expect(cards.length, 'カードが2件').toBe(2)

  // term/meaning が正確に保存されていることを確認
  const foundTerm1 = cards.find((c: Record<string, string>) => c.term === TERM1)
  expect(foundTerm1, `term='${TERM1}' が保存されている`).toBeTruthy()
  expect(foundTerm1?.meaning, `meaning='${MEANING1}' が保存されている`).toBe(MEANING1)

  const foundTerm2 = cards.find((c: Record<string, string>) => c.term === TERM2)
  expect(foundTerm2, `term='${TERM2}' が保存されている`).toBeTruthy()
  expect(foundTerm2?.meaning, `meaning='${MEANING2}' が保存されている`).toBe(MEANING2)
})

// ---------------------------------------------------------------------------
// VOCAB-002（AC-47）: EP #23 で TERM_CARD カードが期間横断で取得できる
// ---------------------------------------------------------------------------
test('VOCAB-002（AC-47）: EP #23 で当日カードが取得できる', async ({ page }) => {
  expect(currentEntryId, 'VOCAB-001 が成功していること（currentEntryId 存在）').toBeTruthy()

  const today = new Date().toISOString().slice(0, 10)

  // EP #23 呼び出し
  const res = await page.request.get(
    `${BE_API}/me/reflections/cards?from=${today}&to=${today}`,
  )
  expect(res.status(), 'EP #23 は 200').toBe(200)

  const body = await res.json()
  const data = body.data ?? body

  expect(typeof data.totalCards, 'totalCards が数値').toBe('number')
  expect(data.totalCards, '少なくとも2枚（当日作成分）').toBeGreaterThanOrEqual(2)

  const cards = (data.cards ?? []) as Record<string, string>[]
  const found1 = cards.find(c => c.term === TERM1)
  const found2 = cards.find(c => c.term === TERM2)
  expect(found1, `term='${TERM1}' がレスポンスに含まれる（AC-47）`).toBeTruthy()
  expect(found2, `term='${TERM2}' がレスポンスに含まれる（AC-47）`).toBeTruthy()

  // 各カードに必須フィールドが揃っている
  expect(found1?.meaning, 'meaning フィールドが正しい').toBe(MEANING1)
  expect(found1?.themeId, 'themeId フィールドがある').toBeTruthy()
  expect(found1?.targetDate, 'targetDate フィールドがある').toBeTruthy()
  expect(found1?.sectionHeading, 'sectionHeading フィールドがある').toBeTruthy()

  // vocab ページに遷移して UI が正常に描画されること
  await page.goto('/reflections/vocab')
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
  await expect(page.locator('body')).not.toContainText('500')
  await expect(page.locator('h1').first()).toBeVisible({ timeout: 10_000 })
})

// ---------------------------------------------------------------------------
// VOCAB-003（AC-51）: マスク中エントリの maskedHint.cardQuiz に answer 側が漏れない
// ---------------------------------------------------------------------------
test('VOCAB-003（AC-51）: マスク中 cardQuiz に answer 側が漏れない（fail-closed）', async ({ page }) => {
  expect(themeId, 'themeId が存在する').toBeTruthy()

  // 過去日（昨日）TERM_CARD エントリ作成 → isMasked=true
  const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)

  const entryRes = await page.request.put(`${BE_API}/me/reflections/entries`, {
    data: {
      themeId,
      targetDate: yesterday,
      structuredContent: {
        main_theme: 'E2E マスク中漏洩テスト',
        sections: [
          {
            type: 'TERM_CARD',
            heading: '漏洩チェックセクション',
            subsections: [],
            cards: [
              { term: 'secretTerm', meaning: 'secretMeaning' },
            ],
          },
        ],
        free_note: '',
      },
    },
  })
  expect(entryRes.status(), '過去日エントリ upsert は 2xx').toBeLessThan(300)

  const entryBody = await entryRes.json()
  const entry = entryBody.data ?? entryBody
  pastEntryId = entry.id ?? ''
  expect(pastEntryId, 'pastEntryId が返る').toBeTruthy()
  expect(entry.isMasked, '過去日エントリは isMasked=true').toBe(true)

  // --- API 漏洩チェック: maskedHint.cardQuiz に answer 側が含まれていないこと ---
  const getRes = await page.request.get(`${BE_API}/me/reflections/entries/${pastEntryId}`)
  expect(getRes.status()).toBe(200)
  const getBody = await getRes.json()
  const fetched = getBody.data ?? getBody

  expect(fetched.isMasked, 'isMasked=true で本文は非表示').toBe(true)
  expect(fetched.structuredContent, 'マスク中は structuredContent=null（AC-51）').toBeNull()

  const maskedHint = fetched.maskedHint
  expect(maskedHint, 'maskedHint が返る').toBeTruthy()

  const cardQuizList: Array<{ prompts?: Array<Record<string, unknown>> }> = maskedHint?.cardQuiz ?? []
  for (const quiz of cardQuizList) {
    for (const prompt of (quiz.prompts ?? [])) {
      const keys = Object.keys(prompt)
      // fail-closed: answer 側フィールドが存在しないこと
      expect(keys, 'prompt に term が漏れていない（AC-51）').not.toContain('term')
      expect(keys, 'prompt に meaning が漏れていない（AC-51）').not.toContain('meaning')
      expect(keys, 'prompt に answer が漏れていない（AC-51）').not.toContain('answer')
      expect(keys, 'prompt に answerText が漏れていない（AC-51）').not.toContain('answerText')
      // cue 側のみ存在すること
      expect(keys, 'promptSide が存在する').toContain('promptSide')
      expect(keys, 'promptText が存在する').toContain('promptText')
    }
  }

  // --- UI 漏洩チェック: /reflections/recall?entry=:id の DOM に 'secretMeaning' が出ないこと ---
  await page.goto(`/reflections/recall?entry=${pastEntryId}`)
  await waitForHydration(page)
  await page.locator('.p-skeleton').first().waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // DOM 全体に 'secretMeaning' が出ていないことを確認（answer 側漏洩チェック）
  const bodyHtml = await page.content()
  expect(bodyHtml, 'DOM に secretMeaning が漏れていないこと（AC-51）').not.toContain('secretMeaning')

  // ページが壊れていないこと
  await expect(page.locator('body')).not.toContainText('500')
})

// ---------------------------------------------------------------------------
// VOCAB-004（AC-59）: EP #23 呼び出しは recall_attempts を書き込まない
// ---------------------------------------------------------------------------
test('VOCAB-004（AC-59）: EP #23 は recall_attempts を書き込まない', async ({ page }) => {
  const today = new Date().toISOString().slice(0, 10)

  // EP #23 を複数回呼んでもエントリの isMasked が変わらないことで副作用なしを確認
  if (pastEntryId) {
    const beforeRes = await page.request.get(`${BE_API}/me/reflections/entries/${pastEntryId}`)
    const beforeBody = await beforeRes.json()
    const beforeMasked = (beforeBody.data ?? beforeBody).isMasked

    // EP #23 を2回呼ぶ
    for (let i = 0; i < 2; i++) {
      const r = await page.request.get(`${BE_API}/me/reflections/cards?from=${today}&to=${today}`)
      expect(r.status(), `EP #23 ${i + 1}回目は 200`).toBe(200)
    }

    // エントリの状態が変わっていないこと（recall_attempts が書き込まれると isMasked が変わることがある）
    const afterRes = await page.request.get(`${BE_API}/me/reflections/entries/${pastEntryId}`)
    const afterBody = await afterRes.json()
    expect((afterBody.data ?? afterBody).isMasked, 'EP #23 後も isMasked が変わらない（recall_attempts 未書込・AC-59）').toBe(beforeMasked)
  }

  // EP #23 レスポンスに recall_attempts 系フィールドが混入していないこと
  const res = await page.request.get(`${BE_API}/me/reflections/cards?from=${today}&to=${today}`)
  const body = await res.json()
  const data = body.data ?? body

  expect(data, 'レスポンスに recallAttempts が混入していない').not.toHaveProperty('recallAttempts')
  const cards = (data.cards ?? []) as Record<string, unknown>[]
  for (const card of cards) {
    expect(card, 'card に recallAttempts が混入していない').not.toHaveProperty('recallAttempts')
  }
})

// ---------------------------------------------------------------------------
// VOCAB-005（AC-61）: 期間内に TERM_CARD がなければ totalCards=0・cards=[]
// ---------------------------------------------------------------------------
test('VOCAB-005（AC-61）: TERM_CARD なし期間では totalCards=0・cards=[]', async ({ page }) => {
  // 遠い過去の日付範囲（TERM_CARD エントリが存在しない期間）
  const from = '2000-01-01'
  const to = '2000-01-31'

  const res = await page.request.get(`${BE_API}/me/reflections/cards?from=${from}&to=${to}`)
  expect(res.status(), 'EP #23 は 200 を返す（AC-61）').toBe(200)

  const body = await res.json()
  const data = body.data ?? body

  expect(data.totalCards, '空期間では totalCards=0（AC-61）').toBe(0)
  expect(Array.isArray(data.cards), 'cards が配列').toBe(true)
  expect(data.cards.length, '空期間では cards=[]（AC-61）').toBe(0)
})
