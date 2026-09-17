export type SurveyScopeRoleName =
  | 'SYSTEM_ADMIN'
  | 'ADMIN'
  | 'DEPUTY_ADMIN'
  | 'MEMBER'
  | 'SUPPORTER'
  | 'GUEST'

export type SurveyManagementContext = 'admin' | 'delegated' | 'creator' | 'authorized'

interface SurveyManagementContextInput {
  canManage: boolean
  roleName: SurveyScopeRoleName | null
  permissions: readonly string[]
  isCreator: boolean
}

/** param/queryが変わる遷移で詳細ページを再生成し、旧スコープ状態を破棄するためのpage key。 */
export function surveyDetailPageKey(route: { fullPath: string }): string {
  return route.fullPath
}

/** APIで取得できた名称だけを表示対象にし、slugを名称として誤表示しない。 */
export function normalizeSurveyScopeName(name: string | null | undefined): string | null {
  const normalized = name?.trim()
  return normalized ? normalized : null
}

/**
 * BEのviewerCanManageを唯一の表示ゲートにしたうえで、利用者へ管理権限の由来を説明する。
 * ロール取得失敗時は委任と断定せず、汎用の認可済み表示へ倒す。
 */
export function resolveSurveyManagementContext(
  input: SurveyManagementContextInput,
): SurveyManagementContext | null {
  if (!input.canManage) return null
  if (input.roleName === 'ADMIN' || input.roleName === 'SYSTEM_ADMIN') return 'admin'
  if (input.roleName === 'DEPUTY_ADMIN' && input.permissions.includes('MANAGE_SURVEYS')) {
    return 'delegated'
  }
  if (input.isCreator) return 'creator'
  return 'authorized'
}
