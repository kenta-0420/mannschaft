import type { ResultsVisibility } from '~/types/survey'

/**
 * 匿名＋リアルタイム結果のプライバシーガード。
 *
 * 設計書 docs/features/F05.4_survey_vote.md §6 セキュリティ考慮事項:
 *
 * > **匿名 + リアルタイム結果のプライバシー制限**: `is_anonymous = TRUE` かつ
 * > `results_visibility = ALWAYS` の場合、回答者数が少ないと「自分が回答した直後の
 * > 結果変化」から回答内容が推測される可能性がある。フロントエンドで「回答者数が5名未満の
 * > 間は集計結果を表示しません」のガードを実装（`response_count < 5` の場合は結果を伏せる。
 * > API 側は返すがフロントで制御。閾値は将来調整可能）
 *
 * BE は結果を返す（伏せるのは FE の責務）。ここは純関数として切り出してあり、
 * 画面をマウントせずに閾値・境界値を単体テストできる。
 */

/**
 * 匿名かつリアルタイム公開のアンケートで、集計結果の表示を許す最小回答者数。
 *
 * 設計書が「閾値は将来調整可能」と明記しているためマジックナンバーを直書きせず、
 * ここ 1 箇所で定義する。変更する場合は
 * tests/unit/utils/surveyResultPrivacy.spec.ts の境界値テストも合わせて更新すること。
 */
export const MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS = 5

/** {@link isResultWithheldForAnonymityPrivacy} の引数。 */
export interface AnonymityPrivacyGuardInput {
  /** 匿名アンケートか（BE `is_anonymous`）。 */
  isAnonymous: boolean
  /** 結果公開設定（FE ドメイン値。`ALL_MEMBERS` が BE の `ALWAYS` に対応）。 */
  resultsVisibility: ResultsVisibility | undefined
  /** 現在の回答者数（BE `stats.responseCount`）。 */
  responseCount: number
}

/**
 * 集計結果をプライバシー保護のため伏せるべきかを判定する。
 *
 * 3 条件の **すべて** を満たすときだけ true を返す:
 *   1. 匿名アンケートである
 *   2. 結果公開設定が `ALL_MEMBERS`（BE の `ALWAYS` = 締切前から中間集計が見える）
 *   3. 回答者数が {@link MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS} 未満
 *
 * 非匿名アンケートや他の公開設定（`AFTER_CLOSE` / `RESPONDENTS` / `CREATOR_ONLY` /
 * `VIEWERS_ONLY`）を巻き添えで塞がないこと。それらは「回答直後の差分から推測される」
 * という本ガードの前提（リアルタイムに集計が動く）を満たさない。
 *
 * NOTE: 閲覧者のロールでは分岐しない。設計書のガード条件はアンケートの属性のみで、
 * ロールの例外を設けていない。むしろ作成者・管理者は未回答者一覧も見られるため、
 * 少数回答時に回答者と回答内容を突き合わせられる最も強い立場にある。
 */
export function isResultWithheldForAnonymityPrivacy(
  input: AnonymityPrivacyGuardInput,
): boolean {
  if (!input.isAnonymous) return false
  if (input.resultsVisibility !== 'ALL_MEMBERS') return false
  return input.responseCount < MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS
}
