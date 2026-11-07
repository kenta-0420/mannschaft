import type {
  ActivitySyncConfirmation,
  ActivitySyncField,
  ActivitySyncPreview,
} from '~/types/activityScheduleSync'
import { activitySyncConfirmation } from '~/utils/activityScheduleSync'

export function useActivityScheduleSync() {
  const api = useApi()
  const preview = ref<ActivitySyncPreview | null>(null)
  const visible = ref(false)
  let resolveChoice: ((confirmation: ActivitySyncConfirmation | null) => void) | null = null
  function finish(confirmation: ActivitySyncConfirmation | null): void {
    const resolve = resolveChoice
    resolveChoice = null
    visible.value = false
    resolve?.(confirmation)
  }
  function apply(selected: Record<number, ActivitySyncField[]>): void {
    if (preview.value) finish(activitySyncConfirmation(preview.value, selected))
  }
  function scheduleOnly(): void {
    if (preview.value) finish(activitySyncConfirmation(preview.value, {}, true))
  }
  function cancel(): void {
    finish(null)
  }
  async function confirm(
    scopeType: 'team' | 'organization',
    scopeId: string,
    scheduleId: number,
    scheduleUpdate: Record<string, unknown>,
    updateScope?: string,
  ): Promise<ActivitySyncConfirmation | null> {
    const result = await api<{ data: ActivitySyncPreview }>(
      `/api/v1/${scopeType === 'team' ? 'teams' : 'organizations'}/${scopeId}/schedules/${scheduleId}/activity-sync-preview`,
      {
        method: 'POST',
        body: { scheduleUpdate, updateScope },
      },
    )
    preview.value = result.data
    if (
      !result.data.activities.some((activity) =>
        activity.changes.some((change) => !change.automatic),
      )
    )
      return activitySyncConfirmation(result.data, {})
    visible.value = true
    return new Promise((resolve) => {
      resolveChoice = resolve
    })
  }
  onBeforeUnmount(cancel)
  return { preview, visible, confirm, apply, scheduleOnly, cancel }
}
