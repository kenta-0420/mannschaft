import { test, expect, type Page } from '@playwright/test'
import { setupAuth, mockSurveyApi, buildRespondent } from './_helpers'

/**
 * F05.4 アンケート画面 E2E — SURVEY-003 督促送信
 *
 * <p>3 ケース:</p>
 * <ul>
 *   <li>SURVEY-003-1: 成功（200）— 成功通知 + 一覧再取得</li>
 *   <li>SURVEY-003-2: クールダウン中（400）— エラー通知 + 一覧不変</li>
 *   <li>SURVEY-003-3: 上限超過（400）— エラー通知 + 一覧不変</li>
 * </ul>
 *
 * <h2>テスト方針</h2>
 *
 * <p>{@code SurveyRespondentsList} コンポーネントは現時点で本番ページに
 * 組み込まれていないため、E2E 専用ページ
 * {@code pages/_test/survey-respondents.vue} にマウントしてテストする。
 * 本コンポーネントが survey 詳細ページへ統合された後は、本 spec を実ページ
 * 経由のアサーションに書き換え、{@code _test} ページは削除すること。</p>
 *
 * <p>API レスポンスは PR#165 の修正後仕様に従い camelCase で返す
 * （{@code surveyId / remindedCount / remainingRemindQuota / message}）。</p>
 */

const TEAM_ID = 1
const SURVEY_ID = 901
const ADMIN_ID = 200

// ---------------------------------------------------------------------------
// 共通モックデータ
// ---------------------------------------------------------------------------

/** 未回答 2 名 + 回答済み 1 名のサンプル一覧。 */
const RESPONDENTS = [
  buildRespondent({
    userId: 11,
    displayName: '山田太郎',
    hasResponded: false,
  }),
  buildRespondent({
    userId: 12,
    displayName: '佐藤花子',
    hasResponded: false,
  }),
  buildRespondent({
    userId: 13,
    displayName: '鈴木一郎',
    hasResponded: true,
    respondedAt: '2026-04-26T10:00:00Z',
  }),
]

/** テスト用ページへの遷移ヘルパー。 */
async function gotoTestPage(page: Page): Promise<void> {
  await page.goto(
    `/_test/survey-respondents?surveyId=${SURVEY_ID}&scopeType=TEAM&scopeId=${TEAM_ID}&canRemind=true`,
  )
}

/** SurveyRespondentsList のレンダリング完了を待つ。 */
async function waitForRespondentsList(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el
  })
  await page.waitForSelector('[data-testid="survey-respondents-list"]', { timeout: 10_000 })
  // onMounted の loadRespondents 完了を待つため、要素の出現で判定
  await page.waitForSelector('[data-testid="respondent-item-11"]', { timeout: 10_000 })
}

/** 未回答タブへ切り替える。 */
async function switchToUnrespondedTab(page: Page): Promise<void> {
  // PrimeVue SelectButton は未回答ラベルを含むボタンを描画する
  const filter = page.locator('[data-testid="respondents-filter"]')
  await filter.getByText(/未回答/).click()
  // 未回答タブ表示後に督促ボタンが出現するのを待つ
  await page.waitForSelector('[data-testid="respondents-remind-button"]', { timeout: 5_000 })
}

// ---------------------------------------------------------------------------
// SURVEY-003-1: 成功
// ---------------------------------------------------------------------------

test.describe('SURVEY-003: 督促送信', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, {
      userId: ADMIN_ID,
      displayName: 'admin-user',
      role: 'ADMIN',
      scopeType: 'TEAM',
      scopeId: TEAM_ID,
    })
  })

  test('SURVEY-003-1: 成功 — showSuccess + 一覧再取得', async ({ page }) => {
    // 督促 API: 200 + camelCase body（PR#165 後の正式仕様）
    await mockSurveyApi(page, {
      respondentsById: { [SURVEY_ID]: RESPONDENTS },
      remindResponse: {
        ok: true,
        status: 200,
        body: {
          data: {
            surveyId: SURVEY_ID,
            remindedCount: 2,
            remainingRemindQuota: 2,
            message: '督促を送信しました',
          },
        },
      },
    })

    await gotoTestPage(page)
    await waitForRespondentsList(page)
    await switchToUnrespondedTab(page)

    // 督促送信ボタン押下と並行して、再取得 GET をキャッチする。
    // onMounted で 1 回目の GET が走っている前提で「2 回目以降」を捕捉するため、
    // waitForRequest の predicate で時刻を見ずに「ボタン押下後」のリクエストを拾う。
    const refreshRequestPromise = page.waitForRequest(
      (req) =>
        req.method() === 'GET' &&
        /\/api\/v1\/teams\/\d+\/surveys\/\d+\/respondents$/.test(req.url()),
      { timeout: 10_000 },
    )

    await page.locator('[data-testid="respondents-remind-button"]').click()

    // 督促送信 POST のレスポンスを待つ → showSuccess → loadRespondents（再取得）の順
    await refreshRequestPromise

    // 成功通知トーストが表示される（remindSuccess の本文）
    await expect(page.getByText(/2名にリマインドを送信しました/)).toBeVisible()
  })

  test('SURVEY-003-2: クールダウン中 — showError + 一覧不変', async ({ page }) => {
    // 1 回目の GET レスポンスを記録するためカウンタ
    let respondentsGetCount = 0
    await page.route(
      '**/api/v1/teams/*/surveys/*/respondents',
      async (route) => {
        if (route.request().method() === 'GET') respondentsGetCount++
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: RESPONDENTS }),
        })
      },
    )
    // 督促 API: 400（COOLDOWN）
    await page.route('**/api/v1/surveys/*/remind', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'COOLDOWN',
            message: '前回の督促から24時間経過していません',
          },
        }),
      })
    })

    await gotoTestPage(page)
    await waitForRespondentsList(page)
    await switchToUnrespondedTab(page)

    // 1 回目の GET（onMounted 経由）が走った後を起点にする
    expect(respondentsGetCount).toBeGreaterThanOrEqual(1)
    const beforeCount = respondentsGetCount

    await page.locator('[data-testid="respondents-remind-button"]').click()

    // エラートーストが表示されるのを待つ
    await expect(page.getByText(/督促の送信に失敗しました/)).toBeVisible()

    // 失敗時は再取得しない仕様（一覧不変）
    expect(respondentsGetCount).toBe(beforeCount)

    // バッジ（未回答 2 件）も変化なし
    await expect(page.locator('[data-testid="respondent-item-11"]')).toBeVisible()
    await expect(page.locator('[data-testid="respondent-item-12"]')).toBeVisible()
  })

  test('SURVEY-003-3: 上限超過 — showError + 一覧不変', async ({ page }) => {
    let respondentsGetCount = 0
    await page.route(
      '**/api/v1/teams/*/surveys/*/respondents',
      async (route) => {
        if (route.request().method() === 'GET') respondentsGetCount++
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: RESPONDENTS }),
        })
      },
    )
    await page.route('**/api/v1/surveys/*/remind', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'QUOTA_EXCEEDED',
            message: '督促の上限回数に達しました',
          },
        }),
      })
    })

    await gotoTestPage(page)
    await waitForRespondentsList(page)
    await switchToUnrespondedTab(page)

    expect(respondentsGetCount).toBeGreaterThanOrEqual(1)
    const beforeCount = respondentsGetCount

    await page.locator('[data-testid="respondents-remind-button"]').click()

    await expect(page.getByText(/督促の送信に失敗しました/)).toBeVisible()

    expect(respondentsGetCount).toBe(beforeCount)

    await expect(page.locator('[data-testid="respondent-item-11"]')).toBeVisible()
    await expect(page.locator('[data-testid="respondent-item-12"]')).toBeVisible()
  })
})
