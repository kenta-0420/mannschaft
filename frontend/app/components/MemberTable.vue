<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
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
}

interface PagedMembers {
  data: Member[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

const api = useApi()
const notification = useNotification()
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

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadMembers()
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ja-JP')
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
    <Column header="参加日" field="joinedAt" style="width: 120px">
      <template #body="{ data }">
        {{ formatDate(data.joinedAt) }}
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
