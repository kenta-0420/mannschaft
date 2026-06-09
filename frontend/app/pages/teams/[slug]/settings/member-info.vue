<script setup lang="ts">
import type {
  MemberInfoFieldResponse,
  CreateMemberInfoFieldRequest,
  MemberInfoFieldType,
  MemberStatusItem,
} from '~/types/memberInfo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = String(route.params.id)
const notification = useNotification()
const memberInfoApi = useMemberInfoApi()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

// ===== フィールド管理 =====
const fields = ref<MemberInfoFieldResponse[]>([])
const fieldsLoading = ref(false)
const showFieldDialog = ref(false)
const editingField = ref<MemberInfoFieldResponse | null>(null)
const fieldSaving = ref(false)
const showDeleteConfirm = ref(false)
const deletingField = ref<MemberInfoFieldResponse | null>(null)
const fieldDeleting = ref(false)

const fieldTypeOptions = [
  { label: t('memberInfo.field.typeText'), value: 'TEXT' },
  { label: t('memberInfo.field.typePhone'), value: 'PHONE' },
  { label: t('memberInfo.field.typeEmail'), value: 'EMAIL' },
  { label: t('memberInfo.field.typeDate'), value: 'DATE' },
]
const intervalOptions = [
  { label: t('memberInfo.field.intervalNone'), value: null },
  { label: t('memberInfo.field.interval12'), value: 12 },
  { label: t('memberInfo.field.interval36'), value: 36 },
  { label: t('memberInfo.field.interval60'), value: 60 },
]

const fieldForm = ref<{
  fieldName: string
  fieldType: MemberInfoFieldType
  isRequired: boolean
  isSensitive: boolean
  refreshIntervalMonths: number | null
}>({
  fieldName: '',
  fieldType: 'TEXT',
  isRequired: false,
  isSensitive: false,
  refreshIntervalMonths: null,
})

async function loadFields() {
  fieldsLoading.value = true
  try {
    const res = await memberInfoApi.getFields(teamId)
    fields.value = res.data
  } catch {
    notification.error(t('common.error.loadFailed'))
  } finally {
    fieldsLoading.value = false
  }
}

function openCreateDialog() {
  editingField.value = null
  fieldForm.value = { fieldName: '', fieldType: 'TEXT', isRequired: false, isSensitive: false, refreshIntervalMonths: null }
  showFieldDialog.value = true
}

function openEditDialog(field: MemberInfoFieldResponse) {
  editingField.value = field
  fieldForm.value = {
    fieldName: field.fieldName,
    fieldType: field.fieldType,
    isRequired: field.isRequired,
    isSensitive: field.isSensitive,
    refreshIntervalMonths: field.refreshIntervalMonths,
  }
  showFieldDialog.value = true
}

async function saveField() {
  if (!fieldForm.value.fieldName.trim()) return
  fieldSaving.value = true
  try {
    if (editingField.value) {
      await memberInfoApi.updateField(teamId, editingField.value.id, fieldForm.value)
    } else {
      const req: CreateMemberInfoFieldRequest = {
        ...fieldForm.value,
        sortOrder: fields.value.length,
      }
      await memberInfoApi.createField(teamId, req)
    }
    notification.success(t('common.saved'))
    showFieldDialog.value = false
    await loadFields()
  } catch {
    notification.error(t('common.error.saveFailed'))
  } finally {
    fieldSaving.value = false
  }
}

function confirmDelete(field: MemberInfoFieldResponse) {
  deletingField.value = field
  showDeleteConfirm.value = true
}

async function deleteField() {
  if (!deletingField.value) return
  fieldDeleting.value = true
  try {
    await memberInfoApi.deleteField(teamId, deletingField.value.id)
    notification.success(t('common.deleted'))
    showDeleteConfirm.value = false
    await loadFields()
  } catch {
    notification.error(t('common.error.saveFailed'))
  } finally {
    fieldDeleting.value = false
  }
}

// ===== ドラッグ＆ドロップ並び替え =====
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)

function onDragStart(index: number) {
  dragIndex.value = index
}

function onDragOver(event: DragEvent, index: number) {
  event.preventDefault()
  dropTargetIndex.value = index
}

function onDragLeave() {
  dropTargetIndex.value = null
}

async function onDrop(toIndex: number) {
  const from = dragIndex.value
  if (from === null || from === toIndex) {
    dragIndex.value = null
    dropTargetIndex.value = null
    return
  }
  const reordered = [...fields.value]
  const [moved] = reordered.splice(from, 1)
  reordered.splice(toIndex, 0, moved!)
  fields.value = reordered
  dragIndex.value = null
  dropTargetIndex.value = null

  const orders = reordered.map((f, idx) => ({ fieldId: f.id, sortOrder: idx }))
  try {
    await memberInfoApi.reorderFields(teamId, { orders })
  } catch {
    notification.error(t('common.error.saveFailed'))
    await loadFields()
  }
}

function onDragEnd() {
  dragIndex.value = null
  dropTargetIndex.value = null
}

// ===== 回答ステータス =====
const statusLoading = ref(false)
const statusData = ref<{ totalMembers: number; completedCount: number; overdueCount: number; members: MemberStatusItem[] } | null>(null)
const remindingMap = ref<Record<number, boolean>>({})

async function loadStatus() {
  statusLoading.value = true
  try {
    const res = await memberInfoApi.getResponseStatus(teamId)
    statusData.value = res.data
  } catch {
    notification.error(t('common.error.loadFailed'))
  } finally {
    statusLoading.value = false
  }
}

async function sendRemind(userId: number) {
  remindingMap.value[userId] = true
  try {
    await memberInfoApi.sendRemind(teamId, userId)
    notification.success(t('memberInfo.settings.remindSent'))
  } catch (e: unknown) {
    const status = (e as { status?: number })?.status
    if (status === 429) {
      notification.warn(t('memberInfo.settings.remindTooSoon'))
    } else {
      notification.error(t('common.error.saveFailed'))
    }
  } finally {
    remindingMap.value[userId] = false
  }
}

// ===== タブ =====
const activeTab = ref('fields')

watch(activeTab, (tab) => {
  if (tab === 'status' && !statusData.value) {
    loadStatus()
  }
})

onMounted(async () => {
  await loadPermissions()
  await loadFields()
})
</script>

<template>
  <div class="p-4">
    <PageHeader :title="$t('memberInfo.settings.title')" class="mb-4" />

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab value="fields">{{ $t('memberInfo.settings.fieldList') }}</Tab>
        <Tab value="status">{{ $t('memberInfo.settings.statusTable') }}</Tab>
      </TabList>

      <TabPanels>
        <!-- タブ1: フィールド管理 -->
        <TabPanel value="fields">
          <div class="mb-3 flex justify-end">
            <Button
              :label="$t('memberInfo.settings.addField')"
              icon="pi pi-plus"
              :disabled="!isAdmin"
              @click="openCreateDialog"
            />
          </div>

          <div v-if="fieldsLoading" class="flex flex-col gap-3">
            <Skeleton height="3.5rem" class="rounded-lg" />
            <Skeleton height="3.5rem" class="rounded-lg" />
          </div>

          <DashboardEmptyState
            v-else-if="fields.length === 0"
            icon="pi pi-list"
            :message="$t('memberInfo.settings.addField')"
          />

          <div v-else class="flex flex-col gap-2">
            <div
              v-for="(field, index) in fields"
              :key="field.id"
              draggable="true"
              class="flex cursor-grab items-center gap-3 rounded-lg border p-3 transition-colors active:cursor-grabbing"
              :class="[
                dragIndex === index
                  ? 'border-primary/40 bg-primary/5 opacity-50'
                  : dropTargetIndex === index
                    ? 'border-primary bg-primary/10'
                    : 'border-surface-200 bg-white dark:border-surface-700 dark:bg-surface-800',
              ]"
              @dragstart="onDragStart(index)"
              @dragover="onDragOver($event, index)"
              @dragleave="onDragLeave"
              @drop="onDrop(index)"
              @dragend="onDragEnd"
            >
              <i class="pi pi-bars text-surface-400" />

              <div class="flex flex-1 flex-wrap items-center gap-2">
                <span class="font-medium">{{ field.fieldName }}</span>
                <Tag :value="t(`memberInfo.field.type${field.fieldType.charAt(0) + field.fieldType.slice(1).toLowerCase()}`)" severity="secondary" class="text-xs" />
                <Tag v-if="field.isRequired" :value="$t('memberInfo.field.required')" severity="danger" class="text-xs" />
                <Tag v-if="field.isSensitive" severity="warn" class="text-xs">
                  <i class="pi pi-lock mr-1 text-xs" />{{ $t('memberInfo.field.sensitive') }}
                </Tag>
                <span v-if="field.refreshIntervalMonths" class="text-xs text-surface-400">
                  {{ intervalOptions.find(o => o.value === field.refreshIntervalMonths)?.label }}
                </span>
              </div>

              <div class="flex gap-1">
                <Button
                  icon="pi pi-pencil"
                  text
                  rounded
                  size="small"
                  :disabled="!isAdmin"
                  @click="openEditDialog(field)"
                />
                <Button
                  icon="pi pi-trash"
                  text
                  rounded
                  size="small"
                  severity="danger"
                  :disabled="!isAdmin"
                  @click="confirmDelete(field)"
                />
              </div>
            </div>
          </div>
        </TabPanel>

        <!-- タブ2: 回答ステータス -->
        <TabPanel value="status">
          <div v-if="statusLoading" class="space-y-3">
            <Skeleton height="2rem" />
            <Skeleton height="12rem" />
          </div>

          <div v-else-if="statusData">
            <!-- サマリー -->
            <div class="mb-4 flex flex-wrap gap-4">
              <div class="rounded-lg border border-surface-200 bg-white p-3 text-center dark:border-surface-700 dark:bg-surface-800">
                <div class="text-2xl font-bold">{{ statusData.totalMembers }}</div>
                <div class="text-xs text-surface-400">{{ $t('memberInfo.status.totalMembers') }}</div>
              </div>
              <div class="rounded-lg border border-green-200 bg-green-50 p-3 text-center dark:border-green-800 dark:bg-green-950">
                <div class="text-2xl font-bold text-green-600">{{ statusData.completedCount }}</div>
                <div class="text-xs text-surface-400">{{ $t('memberInfo.status.completedCount') }}</div>
              </div>
              <div class="rounded-lg border border-red-200 bg-red-50 p-3 text-center dark:border-red-800 dark:bg-red-950">
                <div class="text-2xl font-bold text-red-600">{{ statusData.overdueCount }}</div>
                <div class="text-xs text-surface-400">{{ $t('memberInfo.status.overdueCount') }}</div>
              </div>
            </div>

            <!-- メンバー別ステータステーブル -->
            <DataTable :value="statusData.members" data-key="userId" striped-rows>
              <template #empty>
                <div class="py-8 text-center text-surface-400">{{ $t('memberInfo.status.completed') }}</div>
              </template>

              <Column field="displayName" :header="$t('common.name')" style="min-width: 8rem">
                <template #body="{ data }">
                  <span>{{ data.displayName || `ID: ${data.userId}` }}</span>
                </template>
              </Column>

              <Column :header="$t('memberInfo.settings.statusTable')" style="min-width: 12rem">
                <template #body="{ data }: { data: MemberStatusItem }">
                  <div class="flex flex-wrap gap-1">
                    <span
                      v-for="resp in data.responses"
                      :key="resp.fieldId"
                      :title="`${resp.fieldName}: ${resp.value ?? t('memberInfo.response.notAnswered')}`"
                    >
                      <Tag
                        :value="resp.fieldName"
                        :severity="resp.isOverdue ? 'danger' : resp.confirmedAt ? 'success' : 'secondary'"
                        class="text-xs"
                      />
                    </span>
                  </div>
                </template>
              </Column>

              <Column :header="$t('memberInfo.settings.sendRemind')" style="width: 9rem">
                <template #body="{ data }: { data: MemberStatusItem }">
                  <Button
                    :label="$t('memberInfo.settings.sendRemind')"
                    icon="pi pi-bell"
                    size="small"
                    outlined
                    :loading="remindingMap[data.userId]"
                    :disabled="!isAdmin"
                    @click="sendRemind(data.userId)"
                  />
                </template>
              </Column>
            </DataTable>

            <div class="mt-3 flex justify-end">
              <Button
                icon="pi pi-refresh"
                :label="$t('common.refresh')"
                outlined
                size="small"
                @click="loadStatus"
              />
            </div>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- フィールド作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showFieldDialog"
      :header="editingField ? $t('memberInfo.settings.editField') : $t('memberInfo.settings.addField')"
      modal
      :style="{ width: '480px' }"
    >
      <div class="flex flex-col gap-4 py-2">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('memberInfo.field.name') }} <span class="text-red-500">*</span></label>
          <InputText v-model="fieldForm.fieldName" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('memberInfo.field.type') }}</label>
          <Select
            v-model="fieldForm.fieldType"
            :options="fieldTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('memberInfo.field.refreshInterval') }}</label>
          <Select
            v-model="fieldForm.refreshIntervalMonths"
            :options="intervalOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ $t('memberInfo.field.required') }}</label>
          <ToggleSwitch v-model="fieldForm.isRequired" />
        </div>

        <div class="flex items-center justify-between">
          <div>
            <div class="text-sm font-medium">{{ $t('memberInfo.field.sensitive') }}</div>
            <div class="text-xs text-surface-400">{{ $t('memberInfo.field.sensitiveHint') }}</div>
          </div>
          <ToggleSwitch v-model="fieldForm.isSensitive" />
        </div>
      </div>

      <template #footer>
        <Button :label="$t('common.cancel')" text @click="showFieldDialog = false" />
        <Button
          :label="$t('common.save')"
          :loading="fieldSaving"
          :disabled="!fieldForm.fieldName.trim()"
          @click="saveField"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteConfirm"
      :header="$t('memberInfo.settings.deleteField')"
      modal
      :style="{ width: '380px' }"
    >
      <p>{{ $t('memberInfo.settings.deleteConfirm') }}</p>
      <p v-if="deletingField" class="mt-2 font-medium">「{{ deletingField.fieldName }}」</p>

      <template #footer>
        <Button :label="$t('common.cancel')" text @click="showDeleteConfirm = false" />
        <Button
          :label="$t('common.delete')"
          severity="danger"
          :loading="fieldDeleting"
          @click="deleteField"
        />
      </template>
    </Dialog>
  </div>
</template>
