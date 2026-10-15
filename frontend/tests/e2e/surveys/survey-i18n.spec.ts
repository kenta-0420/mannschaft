import { test, expect } from '@playwright/test'
import type { Locale } from './_helpers'
import {
  buildSurvey,
  gotoTeamSurveys,
  mockSurveyApi,
  setLocale,
  setupAuth,
  waitForSurveyList,
} from './_helpers'

/**
 * F05.4 アンケート画面 — i18n スモーク E2E テスト (SURVEY-004)
 *
 * <p>方針:</p>
 * <ul>
 *   <li>各ロケール(ja/en/zh/ko/es/de) で SurveyList とアンケート作成ダイアログを開き、
 *       ロケールファイルから拾った代表テキストが画面に出ることを確認する</li>
 *   <li>API はモック方式で固定値を返し、ネットワーク状態に依存させない</li>
 *   <li>各 case の期待文字列は frontend/app/locales/{lang}/surveys.json から拾った
 *       実値であり、推測は含まない</li>
 * </ul>
 *
 * <p>仕様書: docs/features/F05.4_survey_screens.md（SURVEY-004 i18n スモーク）</p>
 */

const TEAM_ID = 1
const SURVEY_ID = 101

/**
 * 各ロケールで検証する期待文字列セット。
 *
 * <p>キーごとに対応する surveys.json のパス:</p>
 * <ul>
 *   <li>statusPublished: surveys.statusLabel.PUBLISHED — 一覧のステータスバッジに出る値</li>
 *   <li>createDialogHeader: surveys.create.dialogHeader — 作成ダイアログのヘッダー</li>
 *   <li>createButton: surveys.list.createButton — 一覧右上の作成ボタンラベル</li>
 * </ul>
 */
interface LocaleExpectation {
  locale: Locale
  statusPublished: string
  createDialogHeader: string
  createButton: string
}

const LOCALE_EXPECTATIONS: LocaleExpectation[] = [
  {
    locale: 'ja',
    statusPublished: '受付中',
    createDialogHeader: 'アンケートを作成',
    createButton: 'アンケート作成',
  },
  {
    locale: 'en',
    statusPublished: 'Open',
    createDialogHeader: 'Create a survey',
    createButton: 'Create survey',
  },
  {
    locale: 'zh',
    statusPublished: '进行中',
    createDialogHeader: '创建问卷',
    createButton: '创建问卷',
  },
  {
    locale: 'ko',
    statusPublished: '응답 중',
    createDialogHeader: '설문 작성',
    createButton: '설문 작성',
  },
  {
    locale: 'es',
    statusPublished: 'Abierta',
    createDialogHeader: 'Crear encuesta',
    createButton: 'Crear encuesta',
  },
  {
    locale: 'de',
    statusPublished: 'Geöffnet',
    createDialogHeader: 'Umfrage erstellen',
    createButton: 'Umfrage erstellen',
  },
]

test.describe('SURVEY-004: F05.4 アンケート i18n スモーク', () => {
  for (const expectation of LOCALE_EXPECTATIONS) {
    test(`SURVEY-004: ${expectation.locale} ロケールで PUBLISHED ステータスと作成導線が翻訳される`, async ({
      page,
    }) => {
      // 認証注入 + 一覧モック登録（setLocale 前に済ませる）
      await setupAuth(page, {
        userId: 1,
        displayName: 'e2e_admin',
        role: 'ADMIN',
        scopeType: 'TEAM',
        scopeId: TEAM_ID,
      })

      await mockSurveyApi(page, {
        surveys: [
          buildSurvey({
            id: SURVEY_ID,
            status: 'PUBLISHED',
            title: 'i18n スモーク用アンケート',
          }),
        ],
      })

      await gotoTeamSurveys(page, TEAM_ID)
      await waitForSurveyList(page)

      // ハイドレーション後に Vue App の $i18n.setLocale を呼んで実際にロケールを切り替える。
      // nuxt.config.ts で detectBrowserLanguage.useCookie=false / strategy='no_prefix' のため、
      // localStorage や cookie を入れるだけでは defaultLocale='ja' から切り替わらない。
      await setLocale(page, expectation.locale)

      // 1) 一覧アイテムの PUBLISHED バッジが該当ロケールの値で表示される
      await expect(
        page.getByTestId(`survey-item-status-${SURVEY_ID}`),
      ).toContainText(expectation.statusPublished, { timeout: 10_000 })

      // 2) 一覧右上の「アンケート作成」ボタン文言も翻訳される
      await expect(page.getByTestId('survey-create-button')).toContainText(
        expectation.createButton,
      )

      // 3) ダイアログを開いてヘッダー文言が翻訳されることも確認
      await page.getByTestId('survey-create-button').click()
      await expect(page.getByTestId('survey-create-dialog')).toBeVisible({
        timeout: 10_000,
      })
      // ダイアログヘッダーは PrimeVue Dialog の header slot に注入されるため
      // testid は付与されておらず、role=dialog 内のテキストとして検証する
      await expect(
        page.getByRole('dialog').getByText(expectation.createDialogHeader),
      ).toBeVisible()
    })
  }
})
