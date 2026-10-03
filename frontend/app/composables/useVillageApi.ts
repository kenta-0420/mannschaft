// 後方互換のため、全サブcomposableをマージして返す
// 新規コードは village/ サブディレクトリの各composableを直接インポートしてください
import { useVillageApi as _useVillageApiCore } from './village/useVillageApi'
import { useVillageMembershipApi } from './village/useVillageMembershipApi'
import { useVillageFeatureApi } from './village/useVillageFeatureApi'
import { useVillageEventApi } from './village/useVillageEventApi'
import { useVillageMatchApi } from './village/useVillageMatchApi'
import { useVillagePhase3Api } from './village/useVillagePhase3Api'
import { useVillageAffinityApi } from './village/useVillageAffinityApi'

export { useVillageMembershipApi }
export { useVillageFeatureApi }
export { useVillageEventApi }
export { useVillageMatchApi }
export { useVillagePhase3Api }
export { useVillageAffinityApi }

export function useVillageApi() {
  return {
    ..._useVillageApiCore(),
    ...useVillageMembershipApi(),
    ...useVillageFeatureApi(),
    ...useVillageEventApi(),
    ...useVillageMatchApi(),
    ...useVillagePhase3Api(),
    ...useVillageAffinityApi(),
  }
}

