import { useShiftApi as useShiftApiCore } from './shift/useShiftApi'
import { useShiftAssignmentApi } from './shift/useShiftAssignmentApi'
import { useShiftAutoAssignApi } from './shift/useShiftAutoAssignApi'
import { useShiftAvailabilityApi } from './shift/useShiftAvailabilityApi'
import { useShiftChangeRequestApi } from './shift/useShiftChangeRequestApi'
import { useShiftHourlyRateApi } from './shift/useShiftHourlyRateApi'
import { useShiftPositionApi } from './shift/useShiftPositionApi'
import { useShiftRequestApi } from './shift/useShiftRequestApi'
import { useShiftSlotApi } from './shift/useShiftSlotApi'
import { useShiftSwapApi } from './shift/useShiftSwapApi'
import { useShiftUtilApi } from './shift/useShiftUtilApi'

/**
 * 後方互換: 全サチEcomposable を�Eージして返す、E
 * 既存�E呼び出し�Eは useShiftApi() のままで引き続き動作する、E
 */
export function useShiftApi() {
  return {
    ...useShiftApiCore(),
    ...useShiftPositionApi(),
    ...useShiftSlotApi(),
    ...useShiftRequestApi(),
    ...useShiftSwapApi(),
    ...useShiftAvailabilityApi(),
    ...useShiftHourlyRateApi(),
    ...useShiftAutoAssignApi(),
    ...useShiftChangeRequestApi(),
    ...useShiftAssignmentApi(),
    ...useShiftUtilApi(),
  }
}
