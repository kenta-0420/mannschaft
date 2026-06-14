import { useShiftApi as useShiftApiCore } from './shift/useShiftApi'
import { useShiftAssignmentApi } from './shift/useShiftAssignmentApi'
import { useShiftAutoAssignApi } from './shift/useShiftAutoAssignApi'
import { useShiftAvailabilityApi } from './shift/useShiftAvailabilityApi'
import { useShiftChangeRequestApi } from './shift/useShiftChangeRequestApi'
import { useShiftConstraintApi } from './shift/useShiftConstraintApi'
import { useShiftHourlyRateApi } from './shift/useShiftHourlyRateApi'
import { useShiftPositionApi } from './shift/useShiftPositionApi'
import { useShiftRequestApi } from './shift/useShiftRequestApi'
import { useShiftSlotApi } from './shift/useShiftSlotApi'
import { useShiftSwapApi } from './shift/useShiftSwapApi'
import { useShiftUtilApi } from './shift/useShiftUtilApi'

/**
 * 後方互換: 全サブ composable をマージして返す。
 * 既存の呼び出し元は useShiftApi() のままで引き続き動作する。
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
    ...useShiftConstraintApi(),
    ...useShiftChangeRequestApi(),
    ...useShiftAssignmentApi(),
    ...useShiftUtilApi(),
  }
}
