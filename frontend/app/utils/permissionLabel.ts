/**
 * 権限コード・対象ロールの表示名（F01.2.1 ALIC-2）。
 *
 * 権限カタログの displayName は DB に日本語固定で入っているため、画面には使わず、
 * ロケールファイルの `teamAffiliations.permission_names.*` を引く。
 * 訳が無い権限（新設直後など）はコードのまま表示する（番人 permission-names-coverage.spec.ts が漏れを検出する）。
 */
type Translate = (key: string) => string

/**
 * 権限コードを i18n キーの末尾に変換する。
 * vue-i18n はキーの `.` を階層区切りとして扱うため `_` に置き換える（jobs.manage → jobs_manage）。
 */
export function permissionKey(code: string): string {
  return code.replace(/\./g, '_')
}

/** 権限コードの表示名。訳が無ければコードそのもの。 */
export function permissionLabel(t: Translate, code: string): string {
  const key = `teamAffiliations.permission_names.${permissionKey(code)}`
  const label = t(key)
  return label && label !== key ? label : code
}

/** チーム権限グループの対象ロールの表示名。未知のロールはコードのまま。 */
export function targetRoleLabel(t: Translate, role: string): string {
  if (role === 'MEMBER') return t('teamAffiliations.group_form.target_role_member')
  if (role === 'DEPUTY_ADMIN') return t('teamAffiliations.group_form.target_role_deputy')
  return role
}
