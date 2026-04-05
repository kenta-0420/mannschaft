export interface SupporterItem {
  userId: number
  displayName: string
  avatarUrl: string | null
  followedAt: string
}

export interface ApplicationItem {
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  message: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
}

export function useSupporterManagement(scopeType: Ref<'team' | 'organization'>, scopeId: Ref<number>) {
  const teamApi = useTeamApi()
  const orgApi = useOrganizationApi()
  const notification = useNotification()
  const { handleApiError } = useErrorHandler()

  const autoApprove = ref(false)
  const settingsLoading = ref(false)
  const settingsSaving = ref(false)

  const supporters = ref<SupporterItem[]>([])
  const supportersLoading = ref(false)

  const applications = ref<ApplicationItem[]>([])
  const applicationsLoading = ref(false)

  const selectedApplicationIds = ref<number[]>([])
  const bulkApproving = ref(false)
  const processingIds = ref<number[]>([])

  const pendingApplications = computed(() => applications.value.filter((a) => a.status === 'PENDING'))

  function isTeam() {
    return scopeType.value === 'team'
  }

  async function fetchSettings() {
    settingsLoading.value = true
    try {
      const res = isTeam()
        ? await teamApi.getSupporterSettings(scopeId.value)
        : await orgApi.getSupporterSettings(scopeId.value)
      autoApprove.value = res.data.autoApprove
    } catch {
      // 設定が取得できない場合はデフォルト値を使用
    } finally {
      settingsLoading.value = false
    }
  }

  async function saveAutoApprove(value: boolean) {
    settingsSaving.value = true
    try {
      if (isTeam()) {
        await teamApi.updateSupporterSettings(scopeId.value, { autoApprove: value })
      } else {
        await orgApi.updateSupporterSettings(scopeId.value, { autoApprove: value })
      }
      autoApprove.value = value
      notification.success(value ? '自動承認をONにしました' : '自動承認をOFFにしました')
    } catch (error) {
      handleApiError(error, 'サポーター設定更新')
      autoApprove.value = !value
    } finally {
      settingsSaving.value = false
    }
  }

  async function fetchSupporters() {
    supportersLoading.value = true
    try {
      const res = isTeam()
        ? await teamApi.getSupporters(scopeId.value)
        : await orgApi.getSupporters(scopeId.value)
      supporters.value = res.data
    } catch {
      supporters.value = []
    } finally {
      supportersLoading.value = false
    }
  }

  async function fetchApplications() {
    applicationsLoading.value = true
    try {
      const res = isTeam()
        ? await teamApi.getSupporterApplications(scopeId.value)
        : await orgApi.getSupporterApplications(scopeId.value)
      applications.value = res.data
    } catch {
      applications.value = []
    } finally {
      applicationsLoading.value = false
    }
  }

  async function approve(applicationId: number) {
    processingIds.value.push(applicationId)
    try {
      if (isTeam()) {
        await teamApi.approveSupporterApplication(scopeId.value, applicationId)
      } else {
        await orgApi.approveSupporterApplication(scopeId.value, applicationId)
      }
      notification.success('申請を承認しました')
      await Promise.all([fetchApplications(), fetchSupporters()])
    } catch (error) {
      handleApiError(error, 'サポーター承認')
    } finally {
      processingIds.value = processingIds.value.filter((id) => id !== applicationId)
    }
  }

  async function reject(applicationId: number) {
    processingIds.value.push(applicationId)
    try {
      if (isTeam()) {
        await teamApi.rejectSupporterApplication(scopeId.value, applicationId)
      } else {
        await orgApi.rejectSupporterApplication(scopeId.value, applicationId)
      }
      notification.success('申請を却下しました')
      await fetchApplications()
    } catch (error) {
      handleApiError(error, 'サポーター却下')
    } finally {
      processingIds.value = processingIds.value.filter((id) => id !== applicationId)
    }
  }

  async function bulkApprove() {
    if (selectedApplicationIds.value.length === 0) return
    bulkApproving.value = true
    try {
      if (isTeam()) {
        await teamApi.bulkApproveSupporterApplications(scopeId.value, selectedApplicationIds.value)
      } else {
        await orgApi.bulkApproveSupporterApplications(scopeId.value, selectedApplicationIds.value)
      }
      notification.success(`${selectedApplicationIds.value.length}件の申請を一括承認しました`)
      selectedApplicationIds.value = []
      await Promise.all([fetchApplications(), fetchSupporters()])
    } catch (error) {
      handleApiError(error, 'サポーター一括承認')
    } finally {
      bulkApproving.value = false
    }
  }

  function toggleSelectAll() {
    if (selectedApplicationIds.value.length === pendingApplications.value.length) {
      selectedApplicationIds.value = []
    } else {
      selectedApplicationIds.value = pendingApplications.value.map((a) => a.id)
    }
  }

  function toggleSelect(id: number) {
    if (selectedApplicationIds.value.includes(id)) {
      selectedApplicationIds.value = selectedApplicationIds.value.filter((i) => i !== id)
    } else {
      selectedApplicationIds.value.push(id)
    }
  }

  async function init() {
    await Promise.all([fetchSettings(), fetchApplications(), fetchSupporters()])
  }

  return {
    autoApprove,
    settingsLoading,
    settingsSaving,
    supporters,
    supportersLoading,
    applications,
    applicationsLoading,
    selectedApplicationIds,
    bulkApproving,
    processingIds,
    pendingApplications,
    saveAutoApprove,
    approve,
    reject,
    bulkApprove,
    toggleSelectAll,
    toggleSelect,
    init,
  }
}
