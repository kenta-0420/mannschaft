import { useTeamCrud } from './team/useTeamCrud'
import { useTeamMembers } from './team/useTeamMembers'
import { useTeamSettings } from './team/useTeamSettings'
import { useTeamSupporters } from './team/useTeamSupporters'

/**
 * チーム関連 API のファサード composable。
 *
 * リファクタリング第 11 弾でドメイン別に 4 ファイルへ分割した:
 * - {@link useTeamCrud}        — CRUD・検索・アーカイブ・組織一覧・オーナー移譲
 * - {@link useTeamMembers}     — メンバー管理・招待トークン・権限グループ
 * - {@link useTeamSupporters}  — フォロー（SUPPORTER）・サポーター管理
 * - {@link useTeamSettings}    — アクセス要件・ブロック・コンテンツ有料化
 *
 * 公開関数の名前・シグネチャは分割前と完全に同一を保つ（呼び出し側コードは無改修）。
 * 直接サブ composable を呼び出すこともできる。
 */
export function useTeamApi() {
  const { handleApiError } = useErrorHandler()

  const crud = useTeamCrud()
  const members = useTeamMembers()
  const supporters = useTeamSupporters()
  const settings = useTeamSettings()

  return {
    ...crud,
    ...members,
    ...supporters,
    ...settings,
    handleApiError,
  }
}
