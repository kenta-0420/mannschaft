import type { Ref } from 'vue'
import type { ActivityDetailResponse } from '~/types/activity'

export function useActivityDeletion(record: Ref<ActivityDetailResponse | null>) {
  const { t } = useI18n()
  const confirm = useConfirm()
  const { deleteActivity } = useActivityApi()
  const { handleApiError } = useErrorHandler()
  const notification = useNotification()
  const deleting = ref(false)

  function requestDelete(): void {
    const target = record.value
    if (!target?.canDelete || target.metadataOnly || deleting.value) return
    confirm.require({
      header: t('activity.delete.title'),
      message: t('activity.delete.confirm'),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: t('activity.delete.action'),
      rejectLabel: t('button.cancel'),
      acceptProps: { severity: 'danger', class: 'min-h-11 min-w-11' },
      rejectProps: { class: 'min-h-11 min-w-11' },
      accept: async () => {
        if (
          record.value?.id !== target.id ||
          !record.value.canDelete ||
          record.value.metadataOnly ||
          deleting.value
        )
          return
        deleting.value = true
        try {
          await deleteActivity(target.id)
          notification.success(t('activity.delete.success'))
          if (record.value?.id === target.id) {
            await navigateTo(
              target.scopePublicId
                ? `/${target.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${target.scopePublicId}/activities`
                : '/',
            )
          }
        } catch (error) {
          handleApiError(error, '活動記録削除')
        } finally {
          deleting.value = false
        }
      },
    })
  }
  return { deleting, requestDelete }
}
