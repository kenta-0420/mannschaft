import type { Ref } from 'vue'
import { useConfirm } from 'primevue/useconfirm'
import type { components } from '~/types/generated'

/** 旧 BE は操作フラグを返さないため通常のスコープロールだけを許可する。 */
export type ArchiveTeamSummary = components['schemas']['MyTeamResponse'] & { canManageArchive?: boolean }
export function canManageTeamArchive(team: ArchiveTeamSummary): boolean {
  return team.canManageArchive ?? (team.role === 'ADMIN' || team.role === 'DEPUTY_ADMIN')
}

export function useTeamArchiveAction(
  slug: Ref<string>,
  archived: Ref<boolean>,
  onSuccess: () => Promise<void>,
  knownTarget?: Ref<ArchiveTeamSummary | undefined>,
) {
  const api = useApi()
  const teamApi = useTeamApi()
  const teamStore = useTeamStore()
  const confirm = useConfirm()
  const { t } = useI18n()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()
  const allowed = ref(false)
  const available = computed(() => knownTarget
    ? !!knownTarget.value && canManageTeamArchive(knownTarget.value)
    : allowed.value)
  const busy = ref(false)
  const syncError = ref(false)
  let generation = 0
  let confirmationSeq = 0

  async function findTarget(targetSlug: string) {
    const response = await api<{ data: ArchiveTeamSummary[] }>('/api/v1/me/teams', {
      query: { includeArchived: true },
    })
    return response.data.find(team => team.slug === targetSlug)
  }

  watch(slug, async (targetSlug) => {
    const current = ++generation
    allowed.value = false
    syncError.value = false
    if (!targetSlug || knownTarget) return
    try {
      const target = await findTarget(targetSlug)
      if (current === generation) allowed.value = !!target && canManageTeamArchive(target)
    }
    catch (error) {
      if (current === generation) handleApiError(error, 'チームアーカイブ権限取得')
    }
  }, { immediate: true })
  onScopeDispose(() => { generation++ })

  function request() {
    if (!available.value || busy.value || syncError.value) return
    const confirmationId = ++confirmationSeq
    const targetSlug = slug.value
    const wasArchived = archived.value
    const current = generation
    busy.value = true
    let accepted = false
    let completed = false
    confirm.require({
      message: t(wasArchived ? 'teamShell.archive.unarchiveConfirm' : 'teamShell.archive.archiveConfirm'),
      header: t(wasArchived ? 'teamShell.archive.unarchive' : 'teamShell.archive.archive'),
      icon: 'pi pi-exclamation-triangle',
      rejectLabel: t('button.cancel'),
      acceptLabel: t(wasArchived ? 'teamShell.archive.unarchive' : 'teamShell.archive.archive'),
      reject: () => { if (confirmationId === confirmationSeq) busy.value = false },
      accept: async () => {
        if (accepted || confirmationId !== confirmationSeq) return
        accepted = true
        try {
          // 更新直前に状態と権限を再確認する。
          const target = await findTarget(targetSlug)
          if (current !== generation || slug.value !== targetSlug) return
          allowed.value = !!target && canManageTeamArchive(target)
          if (!target || !canManageTeamArchive(target) || !!target.isArchived !== wasArchived) {
            notification.error(t('teamShell.archive.changed'))
            return
          }
          if (wasArchived) await teamApi.unarchiveTeam(targetSlug)
          else await teamApi.archiveTeam(targetSlug)
          completed = true
          // 成立済み更新は画面移動・unmount 後も共通の所属一覧へ反映する。
          const storeResult = await teamStore.fetchMyTeamsWithResult()
          if (!storeResult.ok) {
            handleApiError(storeResult.error, 'アーカイブ更新後の所属一覧同期')
            if (current === generation && slug.value === targetSlug) {
              syncError.value = true
              notification.error(t('teamShell.archive.syncError'))
            }
            return
          }
          if (current !== generation || slug.value !== targetSlug) return
          await onSuccess()
          notification.success(t(wasArchived ? 'teamShell.archive.unarchived' : 'teamShell.archive.archived'))
        }
        catch (error) {
          if (completed) {
            if (current === generation) syncError.value = true
            handleApiError(error, 'アーカイブ更新後の表示同期')
            notification.error(t('teamShell.archive.syncError'))
          }
          else if (current === generation) handleApiError(error, 'チームアーカイブ変更')
        }
        finally {
          busy.value = false
        }
      },
      onHide: () => { if (!accepted && confirmationId === confirmationSeq) busy.value = false },
    })
  }
  async function retrySync() {
    if (busy.value) return
    busy.value = true
    const current = generation
    const targetSlug = slug.value
    try {
      const result = await teamStore.fetchMyTeamsWithResult()
      if (!result.ok) throw result.error
      if (current !== generation || slug.value !== targetSlug) return
      await onSuccess()
      syncError.value = false
    }
    catch (error) {
      handleApiError(error, 'アーカイブ更新後の再同期')
    }
    finally { busy.value = false }
  }
  return { available, busy, syncError, request, retrySync }
}
