/**
 * F21.1 §5.5「FAQ駆動GEO」用 型定義。
 *
 * バックエンドの FAQ 管理 API（`/api/v1/admin/{teams|organizations}/{id}/faqs`）と
 * 公開 FAQ API（`/api/v1/public/{teams|organizations}/{id}/faqs`）の契約に対応する。
 *
 * - 固定質問の文言はバックエンドに保持されず、`questionKey`（enum 名）のみが返る。
 *   FE 側が `faq.fixed.{questionKey.toLowerCase()}` の i18n キーで質問文を描画する。
 * - カテゴリは団体の種別から BE が解決し、`fixedQuestions` は当該カテゴリの 6 問のみ返る。
 *
 * 設計書: docs/features/F21.1_geo_optimization.md §5.5
 */

/** FAQ カテゴリ（7 種）。固定質問はカテゴリごとに 6 問用意される。 */
export type FaqCategory
  = | 'SPORTS'
    | 'HEALTH'
    | 'EDUCATION'
    | 'BUSINESS'
    | 'COMMUNITY'
    | 'RESIDENCE'
    | 'GENERAL'

/**
 * 固定 FAQ 1 問（管理編集画面用）。
 *
 * 質問文は持たず `questionKey`（例: `"SPORTS_ACTIVITY"`）のみを保持し、
 * FE が i18n（`faq.fixed.sports_activity`）で描画する。
 */
export interface FixedFaqAnswer {
  /** 固定質問キー（FixedFaqQuestion の enum 名）。i18n キー解決に用いる。 */
  questionKey: string
  /** カテゴリ内の表示順（1 始まり）。 */
  displayOrder: number
  /** 回答本文。未回答は null。 */
  answer: string | null
}

/** 自由 FAQ 1 問（管理編集画面用）。新規追加時は `id` を null とする。 */
export interface CustomFaq {
  /** FAQ ID（UUID 文字列）。新規追加時は null。 */
  id: string | null
  /** 質問文（必須・最大 255 文字）。 */
  questionText: string
  /** 回答本文（最大 1000 文字）。 */
  answer: string
  /** 表示順。 */
  displayOrder: number
}

/** FAQ 編集画面用ペイロード（管理 GET レスポンス）。 */
export interface FaqEditorResponse {
  /** 対象団体の FAQ カテゴリ（BE が団体種別から解決）。 */
  category: FaqCategory
  /** 解決カテゴリの固定 6 問（displayOrder 昇順・全件。未回答は answer=null）。 */
  fixedQuestions: FixedFaqAnswer[]
  /** 自由質問（displayOrder 昇順）。 */
  customFaqs: CustomFaq[]
}

/** FAQ 一括 upsert リクエスト（管理 PUT）。成功時 204。 */
export interface SaveFaqRequest {
  /**
   * 固定質問の回答リスト（6 問すべて送る）。
   * answer が空文字の項目はその固定質問の回答をクリア（論理削除）扱い。
   */
  fixedAnswers: { questionKey: string, answer: string }[]
  /** 自由質問リスト（最大 7 件）。 */
  customFaqs: CustomFaq[]
}

/**
 * 公開 FAQ 1 件（公開 GET レスポンス）。
 *
 * 固定質問は `questionKey` が非 null（`questionText` は null）で、FE が i18n で描画する。
 * 自由質問は `questionKey` が null（`questionText` に保存値）。
 * 公開ページ表示・FAQPage JSON-LD 構築のソースとして用いる（足軽 E も使用）。
 */
export interface PublicFaqItem {
  /** 固定質問の i18n キー解決用 enum 名（例: `"SPORTS_ACTIVITY"`）。自由質問では null。 */
  questionKey: string | null
  /** 自由質問の質問文。固定質問では null（FE が i18n で描画）。 */
  questionText: string | null
  /** 回答本文（非空）。 */
  answer: string
}
