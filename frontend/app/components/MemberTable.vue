<script setup lang="ts">
import { MEMBER_CALENDAR_COLORS } from '~/utils/memberCalendarColor'
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  canChangeRole: boolean
  canRemove: boolean
}>()

const emit = defineEmits<{
  roleChanged: []
  memberRemoved: []
}>()

interface Member {
  userId: number
  displayName: string
  avatarUrl: string | null
  roleName: string
  joinedAt: string
  calendarColor?: string | null
}

interface PagedMembers {
  data: Member[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

const api = useApi()
const notification = useNotification()
const { formatDate } = useDatetime()
const { t } = useI18n()
const members = ref<Member[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const page = ref(0)
const rows = ref(20)

async function loadMembers() {
  loading.value = true
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    const response = await api<PagedMembers>(
      `/api/v1/${base}/${props.scopeId}/members?page=${page.value}&size=${rows.value}`
    )
    members.value = response.data
    totalRecords.value = response.meta.totalElements
  }
  catch {
    members.value = []
  }
  finally {
    loading.value = false
  }
}

async function onChangeRole(userId: number, roleId: number) {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${userId}/role`, {
      method: 'PATCH',
      body: { roleId },
    })
    notification.success('ロールを変更しました')
    await loadMembers()
    emit('roleChanged')
  }
  catch {
    notification.error('ロール変更に失敗しました')
  }
}

async function onRemoveMember(userId: number, displayName: string) {
  if (!confirm(`${displayName} をメンバーから除外しますか？`)) return
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${userId}`, { method: 'DELETE' })
    notification.success('メンバーを除外しました')
    await loadMembers()
    emit('memberRemoved')
  }
  catch {
    notification.error('メンバー除外に失敗しました')
  }
}

async function onChangeCalendarColor(userId: number, calendarColor: string) {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${userId}/calendar-color`, {
      method: 'PATCH',
      body: { calendarColor },
    })
    notification.success(t('schedule.memberColor.updated'))
    await loadMembers()
  }
  catch {
    notification.error(t('schedule.memberColor.updateFailed'))
  }
}

async function onResetCalendarColor(userId: number) {
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    await api(`/api/v1/${base}/${props.scopeId}/members/${userId}/calendar-color`, { method: 'DELETE' })
    notification.success(t('schedule.memberColor.resetDone'))
    await loadMembers()
  }
  catch {
    notification.error(t('schedule.memberColor.updateFailed'))
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadMembers()
}

function formatMemberDate(dateStr: string): string {
  return formatDate(dateStr)
}

onMounted(() => loadMembers())

defineExpose({ refresh: loadMembers, changeRole: onChangeRole })
</script>

<template>
  <DataTable
    :value="members"
    :loading="loading"
    lazy
    paginator
    :rows="rows"
    :total-records="totalRecords"
    :rows-per-page-options="[10, 20, 50]"
    data-key="userId"
    @page="onPage"
  >
    <Column header="メンバー" field="displayName">
      <template #body="{ data }">
        <div class="flex items-center gap-3">
          <Avatar
            :image="data.avatarUrl"
            :label="data.avatarUrl ? undefined : data.displayName?.charAt(0)"
            shape="circle"
            size="normal"
          />
          <span class="font-medium">{{ data.displayName }}</span>
        </div>
      </template>
    </Column>
    <Column header="ロール" field="roleName" style="width: 160px">
      <template #body="{ data }">
        <RoleBadge :role="data.roleName" />
      </template>
    </Column>
    <Column :header="t('schedule.memberColor.column')" style="width: 170px">
      <template #body="{ data }">
        <div class="flex items-center gap-2">
          <span
            class="h-4 w-4 shrink-0 rounded-full ring-1 ring-surface-300 dark:ring-surface-600"
            :style="{ backgroundColor: data.calendarColor ?? '#64748b' }"
            :aria-label="t('schedule.memberColor.colorLabel', { name: data.displayName })"
          />
          <Select
            v-if="canChangeRole"
            :model-value="data.calendarColor ?? null"
            :options="MEMBER_CALENDAR_COLORS"
            :placeholder="t('schedule.memberColor.auto')"
            class="min-w-0 flex-1"
            @update:model-value="(color) => color && onChangeCalendarColor(data.userId, color)"
          >
            <template #option="{ option }">
              <span class="flex items-center gap-2"><span class="h-3 w-3 rounded-full" :style="{ backgroundColor: option }" />{{ option }}</span>
            </template>
          </Select>
          <Button
            v-if="canChangeRole && data.calendarColor"
            :aria-label="t('schedule.memberColor.reset')"
            icon="pi pi-replay"
            text
            rounded
            size="small"
            @click="onResetCalendarColor(data.userId)"
          />
        </div>
      </template>
    </Column>
    <Column header="参加日" field="joinedAt" style="width: 120px">
      <template #body="{ data }">
        {{ formatMemberDate(data.joinedAt) }}
      </template>
    </Column>
    <Column v-if="canChangeRole || canRemove" header="操作" style="width: 100px">
      <template #body="{ data }">
        <div class="flex gap-1">
          <Button
            v-if="canRemove"
            icon="pi pi-trash"
            severity="danger"
            text
            rounded
            size="small"
            @click="onRemoveMember(data.userId, data.displayName)"
          />
        </div>
      </template>
    </Column>
    <template #empty>
      <div class="p-4 text-center text-surface-500">
        メンバーはまだいません
      </div>
    </template>
  </DataTable>
</template>
