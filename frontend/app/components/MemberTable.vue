<script setup lang="ts">
import type { TeamReturnStayPlan } from '~/composables/returnStayPlan/useReturnStayPlanApi'
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
}

interface PagedMembers {
  data: Member[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

const api = useApi()
const notification = useNotification()
const { formatDate } = useDatetime()
const members = ref<Member[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const page = ref(0)
const rows = ref(20)
const returnStayApi = useReturnStayPlanTeamApi()
const returnStayPlans = ref<Map<number, TeamReturnStayPlan[]>>(new Map())
const returnStayLoadError = ref(false)
const returnStayLoading = ref(false)
const selectedReturnStayPlan = ref<TeamReturnStayPlan | null>(null)
const returnStayDetailVisible = ref(false)
const returnStayDetailFocus = ref<HTMLButtonElement | null>(null)
let returnStayRequestSequence = 0
let memberRequestSequence = 0
let memberController: AbortController | null = null
function openReturnStayDetail(plan: TeamReturnStayPlan) {
  selectedReturnStayPlan.value = plan
  returnStayDetailVisible.value = true
}

async function focusReturnStayDetail() {
  await nextTick()
  returnStayDetailFocus.value?.focus()
}

function returnStayLocation(plan: TeamReturnStayPlan): string {
  return plan.location.regionName ?? plan.location.prefectureCode ?? plan.location.countryCode
}
async function loadReturnStayPlans() {
  const sequence = ++returnStayRequestSequence
  if (props.scopeType !== 'team' || members.value.length === 0) { returnStayPlans.value = new Map(); returnStayLoadError.value = false; returnStayLoading.value = false; return }
  returnStayLoadError.value = false
  returnStayLoading.value = true
  try {
    const items = await returnStayApi.fetchForMembers(props.scopeId, members.value.map((member) => member.userId))
    if (sequence === returnStayRequestSequence && items) returnStayPlans.value = new Map(items.map((item) => [item.memberId, item.plans]))
  } catch {
    if (sequence === returnStayRequestSequence) { returnStayLoadError.value = true; returnStayPlans.value = new Map() }
  }
  finally { if (sequence === returnStayRequestSequence) returnStayLoading.value = false }
}

async function loadMembers() {
  const sequence = ++memberRequestSequence
  memberController?.abort()
  memberController = new AbortController()
  loading.value = true
  try {
    const base = props.scopeType === 'team' ? 'teams' : 'organizations'
    const response = await api<PagedMembers>(
      `/api/v1/${base}/${props.scopeId}/members?page=${page.value}&size=${rows.value}`,
      { signal: memberController.signal },
    )
    if (sequence !== memberRequestSequence) return
    members.value = response.data
    totalRecords.value = response.meta.totalElements
    await loadReturnStayPlans()
  }
  catch {
    if (sequence !== memberRequestSequence || memberController?.signal.aborted) return
    members.value = []
  }
  finally {
    if (sequence === memberRequestSequence) loading.value = false
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

function formatMemberDate(dateStr: string): string {
  return formatDate(dateStr)
}

onMounted(() => loadMembers())
onBeforeUnmount(() => memberController?.abort())

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
        {{ formatMemberDate(data.joinedAt) }}
      </template>
    </Column>
    <Column v-if="scopeType === 'team'" :header="$t('returnStayPlan.memberPill.title')" style="min-width: 180px">
      <template #body="{ data }">
        <div v-if="returnStayLoading" class="text-xs text-surface-400">{{ $t('common.loading') }}</div>
        <div v-else-if="returnStayLoadError" class="flex items-center gap-1 text-xs text-surface-400">
          <span>{{ $t('returnStayPlan.memberPill.unavailable') }}</span>
          <Button text size="small" :label="$t('common.retry')" @click="loadReturnStayPlans" />
        </div>
        <div v-else class="flex flex-wrap gap-1">
          <button v-for="plan in returnStayPlans.get(data.userId) ?? []" :key="plan.id" type="button" class="rounded focus:outline-none focus:ring-2 focus:ring-primary" :aria-label="$t('returnStayPlan.memberPill.title')" @click="openReturnStayDetail(plan)">
            <Tag :severity="plan.status === 'ACTIVE' ? 'success' : 'info'" :value="plan.status === 'ACTIVE' ? $t(`returnStayPlan.status.${plan.planType}`) : `${plan.startDate}–${plan.endDate}`" />
          </button>
        </div>
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
  <Dialog v-model:visible="returnStayDetailVisible" modal closable close-on-escape dismissable-mask class="return-stay-detail-dialog" :header="$t('returnStayPlan.dialog.title')" :style="{ width: 'min(34rem, calc(100vw - 2rem))' }" :breakpoints="{ '640px': '100vw' }" @show="focusReturnStayDetail">
    <dl v-if="selectedReturnStayPlan" class="grid gap-3">
      <div><dt class="text-sm text-surface-500">{{ $t('returnStayPlan.form.type') }}</dt><dd class="font-medium">{{ $t(`returnStayPlan.planType.${selectedReturnStayPlan.planType}`) }}</dd></div>
      <div><dt class="text-sm text-surface-500">{{ $t('returnStayPlan.form.prefecture') }}</dt><dd class="font-medium">{{ returnStayLocation(selectedReturnStayPlan) }}</dd></div>
      <div><dt class="text-sm text-surface-500">{{ $t('returnStayPlan.form.start') }}–{{ $t('returnStayPlan.form.end') }}</dt><dd class="font-medium">{{ selectedReturnStayPlan.startDate }}–{{ selectedReturnStayPlan.endDate }}</dd></div>
      <button ref="returnStayDetailFocus" type="button" class="justify-self-end rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary" @click="returnStayDetailVisible = false">{{ $t('returnStayPlan.dialog.close') }}</button>
    </dl>
  </Dialog>
</template>

<style scoped>
@media (max-width: 640px) {
  :global(.return-stay-detail-dialog) {
    width: 100vw !important;
    height: 100vh !important;
    max-height: none !important;
    margin: 0 !important;
  }
}
</style>
