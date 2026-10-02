import type { SurveyDetailResponse, SurveyStatus } from '~/types/survey'

/**
 * アンケート詳細画面の「この閲覧者に何をさせてよいか」の判定（CMP-041）。
 *
 * ## なぜ FE が判定を持たないのか
 *
 * かつて詳細画面はロール名（`useRoleAccess` 相当の `isAdmin` / `isAdminOrDeputy`）で
 * 操作ボタンを出し分けていた。CMP-041 で BE がアンケートの管理操作を
 * 「ADMIN または **MANAGE_SURVEYS を持つ** DEPUTY_ADMIN」へ締めた結果、
 * ロール名だけを見る判定では **権限を持たない副管理者にボタンが見えるのに、押すと 403** になる。
 *
 * よって FE は認可ロジックを書かず、BE が詳細応答に載せた判定結果
 * （`viewerCanManage` / `viewerCanViewTeamBreakdown`）にそのまま従う。これらの値は
 * 管理系 API が 403 を投げるのと**同じ判定点**から得ているため、
 * `true` なら通り、`false` なら必ず 403 になる（先例: `viewerCanViewResults`・Issue #2779）。
 *
 * ## fail-closed
 *
 * フラグが欠けた応答（`undefined` / `null`）は**異常**であり、許可へ倒さない。
 * 判断材料が一つも無い状態で許可すると「押すと必ず失敗する導線」が復活するためである。
 */

/** 判定に必要な最小限の詳細応答（テストから部分オブジェクトを渡せるようにする）。 */
export type SurveyViewerCapabilityInput = Pick<
  SurveyDetailResponse['data'],
  'viewerCanManage' | 'viewerCanViewTeamBreakdown'
> | null | undefined

/**
 * 管理操作（締切・設問追加・公開・督促・回答者一覧など）を行えるか。
 *
 * BE 側の定義は「作成者 または ADMIN／MANAGE_SURVEYS 保有 DEPUTY_ADMIN」
 * （`SurveyAccessGuard#canManage`）。
 */
export function canManageSurvey(detail: SurveyViewerCapabilityInput): boolean {
  return detail?.viewerCanManage === true
}

/**
 * チーム別内訳（組織の管理ビュー）を取得できるか。
 *
 * BE の内訳 EP（`SurveyResultService#getTeamBreakdown`）は**作成者高速パスを持たない**ため、
 * {@link canManageSurvey} とは別の項目に従う。
 */
export function canViewSurveyTeamBreakdown(detail: SurveyViewerCapabilityInput): boolean {
  return detail?.viewerCanViewTeamBreakdown === true
}

/**
 * 督促を送信できるか。管理操作可であり、かつ公開中であること
 * （BE `SurveyRemindService#remind` は PUBLISHED 以外を INVALID_SURVEY_STATUS で弾く）。
 */
export function canRemindSurvey(
  detail: SurveyViewerCapabilityInput,
  status: SurveyStatus | undefined,
): boolean {
  return canManageSurvey(detail) && status === 'PUBLISHED'
}
