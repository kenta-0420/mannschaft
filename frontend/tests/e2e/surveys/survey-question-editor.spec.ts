import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  gotoTeamSurveys,
  mockSurveyApi,
  setLocale,
  setupAuth,
  waitForSurveyList,
} from './_helpers'

/**
 * F05.4 アンケート画面 — 設問エディタ操作 E2E テスト (SURVEY-006)
 *
 * <p>方針:</p>
 * <ul>
 *   <li>ja ロケール固定でアンケート作成ダイアログ内 SurveyQuestionEditor を直接操作</li>
 *   <li>API はモック方式（GET /surveys のみ空配列で返せばよい）</li>
 *   <li>assertion は data-testid ベース。questionType の Select は PrimeVue Select の
 *       慣例に従い「testid をクリック → option role でクリック」で操作する</li>
 *   <li>並び替え検証は「Q2 のテキストが ↑ 操作後に question-text-0 へ移動した」で確認する</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F05.4_survey_screens.md（SURVEY-006 設問エディタ）</p>
 */

const TEAM_ID = 1

/**
 * SurveyCreateDialog を開いた状態までセットアップする。
 * - 認証注入（ADMIN）
 * - ロケール ja 固定（i18n キー → 表示文言の対応を安定させるため）
 * - SurveyList を空配列でモック
 * - 「アンケート作成」ボタンをクリックしてダイアログを表示
 */
async function openCreateDialog(page: Page): Promise<void> {
  await setLocale(page, 'ja')
  await setupAuth(page, {
    userId: 1,
    displayName: 'e2e_admin',
    role: 'ADMIN',
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
  })
  await mockSurveyApi(page, { surveys: [] })

  await gotoTeamSurveys(page, TEAM_ID)
  await waitForSurveyList(page)

  await page.getByTestId('survey-create-button').click()
  await expect(page.getByTestId('survey-create-dialog')).toBeVisible({
    timeout: 10_000,
  })
  await expect(page.getByTestId('survey-question-editor')).toBeVisible()
}

/**
 * 設問追加ボタンをクリックして N 件目の設問カードが表示されるのを待つ。
 * @param expectedIndex 期待される追加後の最後のカード index（0-origin）
 */
async function addQuestionAndWait(page: Page, expectedIndex: number): Promise<void> {
  await page.getByTestId('question-add').click()
  await expect(page.getByTestId(`question-card-${expectedIndex}`)).toBeVisible({
    timeout: 5_000,
  })
}

/**
 * PrimeVue Select で指定 option を選択する。
 * Select 本体（testid 付き wrapper）をクリックして展開し、
 * 表示文言で role=option をクリックする。
 */
async function selectQuestionType(
  page: Page,
  index: number,
  optionLabel: string,
): Promise<void> {
  await page.getByTestId(`question-type-${index}`).click()
  await page.getByRole('option', { name: optionLabel }).click()
}

test.describe('SURVEY-006: F05.4 アンケート設問エディタ操作', () => {
  test('SURVEY-006-1: 設問追加ボタンで設問カードが 1 件増える', async ({ page }) => {
    await openCreateDialog(page)

    // 初期は設問 0 件で空状態（question-card-0 が無いことの確認）
    await expect(page.getByTestId('question-card-0')).toBeHidden()

    await addQuestionAndWait(page, 0)
    await expect(page.getByTestId('question-card-0')).toBeVisible()
    // 入力欄もすぐ使える状態であること
    await expect(page.getByTestId('question-text-0')).toBeVisible()

    // もう一度押せば 2 件目が並ぶ
    await addQuestionAndWait(page, 1)
    await expect(page.getByTestId('question-card-1')).toBeVisible()
  })

  test('SURVEY-006-2: ↑ ボタンで設問の並び替えができる', async ({ page }) => {
    await openCreateDialog(page)

    // 2 件追加し、それぞれに識別可能な文章を入れる
    await addQuestionAndWait(page, 0)
    await page.getByTestId('question-text-0').fill('Q1 オリジナル')

    await addQuestionAndWait(page, 1)
    await page.getByTestId('question-text-1').fill('Q2 オリジナル')

    // 初期状態の確認
    await expect(page.getByTestId('question-text-0')).toHaveValue('Q1 オリジナル')
    await expect(page.getByTestId('question-text-1')).toHaveValue('Q2 オリジナル')

    // Q2 の ↑ ボタンで Q2 を Q1 の位置に持ち上げる
    await page.getByTestId('question-move-up-1').click()

    // 並び順が入れ替わったので、index 0 は元の Q2、index 1 は元の Q1
    await expect(page.getByTestId('question-text-0')).toHaveValue('Q2 オリジナル')
    await expect(page.getByTestId('question-text-1')).toHaveValue('Q1 オリジナル')
  })

  test('SURVEY-006-3: 削除ボタンで設問が消える', async ({ page }) => {
    await openCreateDialog(page)

    await addQuestionAndWait(page, 0)
    await page.getByTestId('question-text-0').fill('削除予定の設問')

    await page.getByTestId('question-delete-0').click()

    // 設問が 0 件に戻り、空状態のメッセージが見える
    await expect(page.getByTestId('question-card-0')).toBeHidden()
    await expect(
      page.getByTestId('survey-question-editor').getByText('設問がありません'),
    ).toBeVisible()
  })

  test('SURVEY-006-4: タイプを TEXT に切り替えると選択肢編集 UI が消える', async ({ page }) => {
    await openCreateDialog(page)

    await addQuestionAndWait(page, 0)

    // 初期タイプは SINGLE_CHOICE → 選択肢追加ボタンが見える
    await expect(page.getByTestId('question-add-option-0')).toBeVisible()
    // option-{q}-{o} 入力欄も初期 2 件分作られる
    await expect(page.getByTestId('question-option-0-0')).toBeVisible()

    // TEXT に切替
    await selectQuestionType(page, 0, '自由記述')

    // 選択肢編集 UI が消える（addOption ボタンも option 入力欄も hidden）
    await expect(page.getByTestId('question-add-option-0')).toBeHidden()
    await expect(page.getByTestId('question-option-0-0')).toBeHidden()

    // TEXT 用の補足文言が表示される（i18n: surveys.create.questionEditor.textHint）
    await expect(
      page.getByTestId('question-card-0').getByText('回答者は自由記述で回答します'),
    ).toBeVisible()
  })

  test('SURVEY-006-5: タイプを RATING に切り替えると選択肢編集が隠れて評価スケール文言が出る', async ({
    page,
  }) => {
    await openCreateDialog(page)

    await addQuestionAndWait(page, 0)

    await expect(page.getByTestId('question-add-option-0')).toBeVisible()

    // RATING に切替
    await selectQuestionType(page, 0, '評価（1〜5）')

    // 選択肢編集 UI は隠れる
    await expect(page.getByTestId('question-add-option-0')).toBeHidden()

    // 評価スケール文言が表示される（i18n: surveys.create.questionEditor.ratingScale）
    await expect(
      page.getByTestId('question-card-0').getByText('評価スケール（1〜5）'),
    ).toBeVisible()
  })
})
