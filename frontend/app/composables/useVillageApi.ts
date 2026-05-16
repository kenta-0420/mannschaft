// 後方互換のため、全サブcomposableをマージして返す
// 新規コードは village/ サブディレクトリの各composableを直接インポートしてください
import { useVillageApi as _useVillageApiCore } from './village/useVillageApi'
import { useVillageMembershipApi } from './village/useVillageMembershipApi'
import { useVillageFeatureApi } from './village/useVillageFeatureApi'
import { useVillageEventApi } from './village/useVillageEventApi'
import { useVillageMatchApi } from './village/useVillageMatchApi'

export { useVillageMembershipApi }
export { useVillageFeatureApi }
export { useVillageEventApi }
export { useVillageMatchApi }

export function useVillageApi() {
  return {
    ..._useVillageApiCore(),
    ...useVillageMembershipApi(),
    ...useVillageFeatureApi(),
    ...useVillageEventApi(),
    ...useVillageMatchApi(),
  }
}

