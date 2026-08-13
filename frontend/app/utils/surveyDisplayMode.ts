import type { SurveyStatus } from '~/types/survey'

/**
 * アンケート詳細画面の表示モード。
 *
 * - `draft`: DRAFT の作成者・ADMIN+ 向けプレビュー
 * - `response`: 回答フォーム（SurveyResponseForm）
 * - `results`: 集計パネル
 * - `results-withheld-privacy`: 匿名＋リアルタイム＋少数回答のため集計を伏せている説明
 * - `closed-no-permission`: 締切済みかつ結果閲覧権限なし
 */
export type SurveyDisplayMode =
  | 'response'
  | 'results'
  | 'results-withheld-privacy'
  | 'closed-no-permission'
  | 'draft'

/** {@link resolveSurveyDisplayMode} の引数。 */
export interface SurveyDisplayModeInput {
  /** アンケートの状態。 */
  status: SurveyStatus | undefined
  /** 結果閲覧権限（設計書 §権限判定）。 */
  canViewResults: boolean
  /**
   * 匿名＋リアルタイム＋少数回答のため集計を伏せるか
   * （utils/surveyResultPrivacy.ts の判定結果）。
   */
  resultsWithheldForPrivacy: boolean
  /** 自分が既に回答済みか。詳細レスポンスの `hasResponded` をそのまま渡す。 */
  hasResponded: boolean
  /** 複数回答が許可されているか（BE `allow_multiple_submissions`）。 */
  allowMultipleSubmissions: boolean
  /**
   * 結果画面の回答導線（「このアンケートに回答する」）が押されたか。
   * 押されている間だけ回答フォームへ切り替える。
   */
  responseRequested: boolean
}

/** {@link shouldShowRespondCta} の引数。 */
export interface RespondCtaInput {
  status: SurveyStatus | undefined
  hasResponded: boolean
  allowMultipleSubmissions: boolean
}

/**
 * 結果画面に回答導線（CTA）を出すべきか。
 *
 * ## なぜ結果画面に回答導線が要るのか
 *
 * `ALL_MEMBERS`（BE の `ALWAYS`）は「未回答 MEMBER も結果画面に直接遷移できる」のが仕様である
 * （設計書 docs/features/F05.4_survey_vote.md L1377 付近「結果閲覧権限の判定」）。
 * つまり結果画面が出ること自体は正しい。**欠けていたのは「そこから回答できないこと」**だった。
 *
 * `SurveyResultsPanel` には回答導線が無いため、`ALWAYS` のアンケートは
 *   - 非匿名なら公開直後から、
 *   - 匿名でもプライバシーガードが外れた（回答が閾値に達した）瞬間から、
 * 未回答者が結果画面に固定され、UI から回答を集めきれなくなっていた。
 *
 * 分岐を引っくり返して `response` を優先させると上記の仕様に反するため、
 * **結果画面側に回答導線を置く**ことで解く。
 *
 * 条件:
 *   - `PUBLISHED` である（`DRAFT`/`CLOSED` は回答を受け付けない）
 *   - 未回答である、または複数回答が許可されている（回答の修正が可能）
 */
export function shouldShowRespondCta(input: RespondCtaInput): boolean {
  if (input.status !== 'PUBLISHED') return false
  if (!input.hasResponded) return true
  // 回答済みでも、複数回答が許可されていれば回答し直せる
  return input.allowMultipleSubmissions
}

/**
 * 表示モードを決める。分岐の優先順位そのものを単体テストで固定するために純関数へ切り出す。
 *
 * ## プライバシーガードが回答導線を食い潰さないこと（重要）
 *
 * 匿名＋`ALL_MEMBERS`＋`PUBLISHED` のアンケートは**公開直後は必ず回答0件**である。
 * このとき全メンバーで `canViewResults = true` かつ `resultsWithheldForPrivacy = true` になる。
 * かつて「伏せる画面」を `response` より先に判定していたため、未回答者にも説明画面が出て
 * 回答フォームへ到達できず、
 *
 *   誰も回答できない → 回答数が閾値に達しない → ガードが永久に解除されない
 *
 * という詰みが起きていた（匿名＋常時公開のアンケートが1件も回答を集められない）。
 *
 * ガードは「集計を見せない」ためのものであって「回答させない」ためのものではない。
 * よって**結果部分だけを伏せ、回答導線は残す**:
 *   - 未回答かつ回答可能（PUBLISHED）→ `response`（回答してもらう）
 *   - 回答済み、または締切済みで回答手段が無い → `results-withheld-privacy`（理由と再開条件を説明）
 */
export function resolveSurveyDisplayMode(input: SurveyDisplayModeInput): SurveyDisplayMode {
  const {
    status,
    canViewResults,
    resultsWithheldForPrivacy,
    hasResponded,
    allowMultipleSubmissions,
    responseRequested,
  } = input

  // DRAFT は作成者・ADMIN+ 向けのプレビュー画面
  if (status === 'DRAFT') return 'draft'

  if (canViewResults) {
    // 結果画面（または集計を伏せた画面）の回答導線が押されたら回答フォームへ。
    // 導線を出せない状態（CLOSED・回答済みで複数回答不可）では効かせない。
    if (
      responseRequested &&
      shouldShowRespondCta({ status, hasResponded, allowMultipleSubmissions })
    ) {
      return 'response'
    }

    // 設計書 docs/features/F05.4_survey_vote.md L1377〜「結果閲覧権限の判定」に準拠:
    // 結果閲覧権限を持つユーザーは、回答可否より優先して結果画面を表示する。
    // （未回答者が結果画面に固定されないよう、結果画面には回答導線を置くこと。
    //   shouldShowRespondCta の説明を参照）
    if (!resultsWithheldForPrivacy) return 'results'

    // 集計は伏せる。ただし回答導線までは消さない（上の「詰み」を作らないため）。
    // まだ回答しておらず、回答を受け付けている間は回答フォームを出す。
    if (status === 'PUBLISHED' && !hasResponded) return 'response'

    // 回答済み、または締切済みで回答手段が無い場合のみ、
    // 黙って空にせず「なぜ見えないのか・いつ見えるのか」を説明する。
    return 'results-withheld-privacy'
  }

  // 結果閲覧不可の場合のフォールバック分岐。
  // PUBLISHED: 未回答も回答済みも 'response'（SurveyResponseForm 側で「回答済み」表示へ）。
  if (status === 'PUBLISHED') return 'response'

  // CLOSED かつ結果閲覧権限なし → 非公開メッセージ。
  return 'closed-no-permission'
}
