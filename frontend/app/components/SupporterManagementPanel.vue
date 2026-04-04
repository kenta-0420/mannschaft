<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

interface SupporterItem {
  userId: number
  displayName: string
  avatarUrl: string | null
  followedAt: string
}

interface ApplicationItem {
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  message: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  createdAt: string
}

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

async function fetchSettings() {
  settingsLoading.value = true
  try {
    const res =
      props.scopeType === 'team'
        ? await teamApi.getSupporterSettings(props.scopeId)
        : await orgApi.getSupporterSettings(props.scopeId)
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
    if (props.scopeType === 'team') {
      await teamApi.updateSupporterSettings(props.scopeId, { autoApprove: value })
    } else {
      await orgApi.updateSupporterSettings(props.scopeId, { autoApprove: value })
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
    const res =
      props.scopeType === 'team'
        ? await teamApi.getSupporters(props.scopeId)
        : await orgApi.getSupporters(props.scopeId)
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
    const res =
      props.scopeType === 'team'
        ? await teamApi.getSupporterApplications(props.scopeId)
        : await orgApi.getSupporterApplications(props.scopeId)
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
    if (props.scopeType === 'team') {
      await teamApi.approveSupporterApplication(props.scopeId, applicationId)
    } else {
      await orgApi.approveSupporterApplication(props.scopeId, applicationId)
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
    if (props.scopeType === 'team') {
      await teamApi.rejectSupporterApplication(props.scopeId, applicationId)
    } else {
      await orgApi.rejectSupporterApplication(props.scopeId, applicationId)
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
    if (props.scopeType === 'team') {
      await teamApi.bulkApproveSupporterApplications(props.scopeId, selectedApplicationIds.value)
    } else {
      await orgApi.bulkApproveSupporterApplications(props.scopeId, selectedApplicationIds.value)
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

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

onMounted(async () => {
  await Promise.all([fetchSettings(), fetchApplications(), fetchSupporters()])
})
</script>

<template>
  <div class="space-y-6">
    <!-- 承認設定 -->
    <div class="rounded-lg border p-4">
      <h3 class="mb-3 font-semibold">承認設定</h3>
      <div v-if="settingsLoading" class="flex items-center gap-2 text-gray-500">
        <i class="pi pi-spin pi-spinner" />
        <span class="text-sm">読み込み中...</span>
      </div>
      <div v-else class="flex items-center justify-between">
        <div>
          <p class="font-medium">自動承認</p>
          <p class="text-sm text-gray-500">ONにするとサポーター申請を自動で承認します</p>
        </div>
        <ToggleSwitch
          :model-value="autoApprove"
          :disabled="settingsSaving"
          @update:model-value="saveAutoApprove"
        />
      </div>
    </div>

    <!-- 保留中の申請 -->
    <div v-if="!autoApprove" class="rounded-lg border p-4">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold">
          承認待ちの申請
          <Badge
            v-if="pendingApplications.length > 0"
            :value="pendingApplications.length"
            severity="warn"
            class="ml-2"
          />
        </h3>
        <Button
          v-if="pendingApplications.length > 0"
          label="一括承認"
          icon="pi pi-check-circle"
          size="small"
          :disabled="selectedApplicationIds.length === 0"
          :loading="bulkApproving"
          @click="bulkApprove"
        />
      </div>

      <div v-if="applicationsLoading" class="flex justify-center py-6">
        <ProgressSpinner style="width: 32px; height: 32px" />
      </div>
      <div
        v-else-if="pendingApplications.length === 0"
        class="rounded-lg border border-dashed border-gray-300 py-8 text-center text-sm text-gray-500"
      >
        <i class="pi pi-inbox mb-2 text-2xl" />
        <p>承認待ちの申請はありません</p>
      </div>
      <div v-else class="space-y-2">
        <!-- 全選択 -->
        <div class="flex items-center gap-2 border-b pb-2">
          <Checkbox
            :model-value="selectedApplicationIds.length === pendingApplications.length"
            binary
            @change="toggleSelectAll"
          />
          <span class="text-sm text-gray-500"
            >すべて選択（{{ pendingApplications.length }}件）</span
          >
        </div>
        <!-- 申請リスト -->
        <div
          v-for="app in pendingApplications"
          :key="app.id"
          class="flex items-center gap-3 rounded-lg border p-3"
        >
          <Checkbox
            :model-value="selectedApplicationIds.includes(app.id)"
            binary
            @change="toggleSelect(app.id)"
          />
          <Avatar
            :image="app.avatarUrl ?? undefined"
            :label="app.avatarUrl ? undefined : app.displayName.charAt(0)"
            shape="circle"
            size="normal"
          />
          <div class="min-w-0 flex-1">
            <p class="font-medium">
              {{ app.displayName }}
            </p>
            <p v-if="app.message" class="truncate text-sm text-gray-500">
              {{ app.message }}
            </p>
            <p class="text-xs text-gray-400">申請日: {{ formatDate(app.createdAt) }}</p>
          </div>
          <div class="flex shrink-0 gap-2">
            <Button
              label="承認"
              icon="pi pi-check"
              size="small"
              severity="success"
              :loading="processingIds.includes(app.id)"
              @click="approve(app.id)"
            />
            <Button
              label="却下"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              :loading="processingIds.includes(app.id)"
              @click="reject(app.id)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 現在のサポーター一覧 -->
    <div class="rounded-lg border p-4">
      <h3 class="mb-3 font-semibold">
        サポーター一覧
        <span class="ml-1 text-sm font-normal text-gray-500">（{{ supporters.length }}人）</span>
      </h3>
      <div v-if="supportersLoading" class="flex justify-center py-6">
        <ProgressSpinner style="width: 32px; height: 32px" />
      </div>
      <div
        v-else-if="supporters.length === 0"
        class="rounded-lg border border-dashed border-gray-300 py-8 text-center text-sm text-gray-500"
      >
        <i class="pi pi-heart mb-2 text-2xl" />
        <p>まだサポーターがいません</p>
      </div>
      <div v-else class="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="supporter in supporters"
          :key="supporter.userId"
          class="flex items-center gap-3 rounded-lg border p-3"
        >
          <Avatar
            :image="supporter.avatarUrl ?? undefined"
            :label="supporter.avatarUrl ? undefined : supporter.displayName.charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <p class="truncate font-medium">
              {{ supporter.displayName }}
            </p>
            <p class="text-xs text-gray-400">{{ formatDate(supporter.followedAt) }}から</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
