import { useFacilityCrud } from './facility/useFacilityCrud'
import { useFacilityBooking } from './facility/useFacilityBooking'
import { useFacilitySettings } from './facility/useFacilitySettings'

/**
 * 施設管理関連 API のファサード composable。
 *
 * リファクタリング第 12 弾でドメイン別に 3 ファイルへ分割した:
 * - {@link useFacilityCrud}     — 施設 CRUD・空き状況・利用料・利用規約・備品
 * - {@link useFacilityBooking}  — 予約 CRUD・承認/却下/チェックイン/完了・支払い・確認書 PDF
 * - {@link useFacilitySettings} — スコープ単位設定・統計
 *
 * 公開関数の名前・シグネチャは分割前と完全に同一を保つ（呼び出し側コードは無改修）。
 * 直接サブ composable を呼び出すこともできる。
 */
export function useFacilityApi() {
  const crud = useFacilityCrud()
  const booking = useFacilityBooking()
  const settings = useFacilitySettings()

  return {
    ...crud,
    ...booking,
    ...settings,
  }
}
