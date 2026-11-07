import type {
  ActivitySyncConfirmation,
  ActivitySyncField,
  ActivitySyncPreview,
} from '~/types/activityScheduleSync'

export function activitySyncConfirmation(
  preview: ActivitySyncPreview,
  selected: Record<number, ActivitySyncField[]>,
  scheduleOnly = false,
): ActivitySyncConfirmation {
  return {
    expectedScheduleState: preview.expectedScheduleState,
    activities: preview.activities.map((activity) => ({
      id: activity.id,
      version: activity.version,
      applyFields: scheduleOnly
        ? []
        : activity.changes
            .filter((change) => change.automatic || selected[activity.id]?.includes(change.field))
            .map((change) => change.field),
    })),
  }
}
