import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * F01.2.1 ALIC-2 — 権限グループ画面のテストで「期待する表示文字列」をロケール JSON から引く。
 * 手書きの訳文をテストに持たないため（訳の修正でテストが壊れないように）。
 *
 * 表示側の規約（出陣で `utils/permissionLabel.ts` に実装）:
 * - 権限: `teamAffiliations.permission_names.<コードの . を _ に>`。訳が無ければコードそのもの
 * - 対象ロール: MEMBER → `group_form.target_role_member`、DEPUTY_ADMIN → `group_form.target_role_deputy`、
 *   それ以外はコードのまま。`permission_group_list.target_role`（{role}）に埋め込む
 */
type Locale = 'ja' | 'en' | 'zh' | 'ko' | 'es' | 'de'

interface TeamAffiliationsJson {
  teamAffiliations: {
    permission_names?: Record<string, string>
    permission_group_list: { target_role: string }
    group_form: { target_role_member: string, target_role_deputy: string }
  }
}

function load(locale: Locale): TeamAffiliationsJson['teamAffiliations'] {
  const path = resolve(__dirname, `../../../app/locales/${locale}/team_affiliations.json`)
  return (JSON.parse(readFileSync(path, 'utf8')) as TeamAffiliationsJson).teamAffiliations
}

/** 権限コードの訳（キー未設置なら undefined。テスト側で red の理由として扱う）。 */
export function expectedPermissionLabel(locale: Locale, code: string): string | undefined {
  return load(locale).permission_names?.[code.replace(/\./g, '_')]
}

/** 「対象: メンバー」などの対象ロール表示。未知のロールはコードのまま埋め込む。 */
export function expectedTargetRoleLabel(locale: Locale, role: string): string {
  const json = load(locale)
  const name = role === 'MEMBER'
    ? json.group_form.target_role_member
    : role === 'DEPUTY_ADMIN' ? json.group_form.target_role_deputy : role
  return json.permission_group_list.target_role.replace('{role}', name)
}
