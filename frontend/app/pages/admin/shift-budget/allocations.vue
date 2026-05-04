<script setup lang="ts">
import type {
  AllocationCreateRequest,
  AllocationResponse,
  AllocationUpdateRequest,
} from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 予算割当 CRUD 画面 (`/admin/shift-budget/allocations`)。
 *
 * <p>設計書 §7.1 に準拠。一覧 + 新規作成モーダル + 編集モーダル + 論理削除確認。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>BUDGET_VIEW: 一覧・詳細閲覧（誰でも閲覧可、Service 層で 403 を返却）</li>
 *   <li>BUDGET_ADMIN: 作成・編集・削除（バックエンドが権限不足時 403 を返す）</li>
 * </ul>
 *
 * <p>組織スコープが選択されていない場合は誘導メッセージを表示する。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const allocations = ref<AllocationResponse[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const loading = ref(false)

const formVisible = ref(false)
const editingAllocation = ref<AllocationResponse | null>(null)

const deleteConfirmVisible = ref(false)
const deleteTarget = ref<AllocationResponse | null>(null)

async function load() {
  if (!organizationId.value) {
    allocations.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await api.listAllocations(organizationId.value, page.value, pageSize.value)
    allocations.value = res.items
    total.value = res.total
  }
  catch {
    notification.error(t('shiftBudget.allocation.loadError'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editingAllocation.value = null
  formVisible.value = true
}

function openEdit(allocation: AllocationResponse) {
  editingAllocation.value = allocation
  formVisible.value = true
}

async function handleCreate(payload: AllocationCreateRequest) {
  if (!organizationId.value) return
  try {
    await api.createAllocation(organizationId.value, payload)
    notification.success(t('shiftBudget.allocation.createSuccess'))
    formVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.createError'))
  }
}

async function handleUpdate(args: { id: number; request: AllocationUpdateRequest }) {
  if (!organizationId.value) return
  try {
    await api.updateAllocation(organizationId.value, args.id, args.request)
    notification.success(t('shiftBudget.allocation.updateSuccess'))
    formVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.updateError'))
  }
}

function openDelete(allocation: AllocationResponse) {
  deleteTarget.value = allocation
  deleteConfirmVisible.value = true
}

async function confirmDelete() {
  if (!deleteTarget.value || !organizationId.value) return
  try {
    await api.deleteAllocation(organizationId.value, deleteTarget.value.id)
    notification.success(t('shiftBudget.allocation.deleteSuccess'))
    deleteConfirmVisible.value = false
    deleteTarget.value = null
    await load()
  }
  catch {
    notification.error(t('shiftBudget.allocation.deleteError'))
  }
}

function consumptionRate(allocation: AllocationResponse): number {
  const allocated = allocation.allocated_amount ?? 0
  const consumed = allocation.consumed_amount ?? 0
  if (allocated === 0) return 0
  return consumed / allocated
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  pageSize.value = event.rows
  load()
}

watch(organizationId, () => load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('shiftBudget.allocation.list')" />
      <Button
        v-if="organizationId"
        :label="t('shiftBudget.allocation.create')"
        icon="pi pi-plus"
        @click="openCreate"
      />
    </div>

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <DataTable
      v-else
      :value="allocations"
      striped-rows
      data-key="id"
      :paginator="total > pageSize"
      :rows="pageSize"
      :total-records="total"
      :first="page * pageSize"
      lazy
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ t('shiftBudget.allocation.empty') }}</div>
      </template>
      <Column field="id" :header="t('shiftBudget.allocation.id')" style="width: 80px" />
      <Column field="team_id" :header="t('shiftBudget.allocation.team')" style="width: 100px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.team_id ?? '-' }}</span>
        </template>
      </Column>
      <Column field="project_id" :header="t('shiftBudget.allocation.project')" style="width: 100px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.project_id ?? '-' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.period')" style="width: 200px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span class="text-sm">{{ data.period_start }} 〜 {{ data.period_end }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.amount')" style="width: 140px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span class="font-medium">
            {{ data.allocated_amount?.toLocaleString() ?? '-' }} {{ data.currency }}
          </span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.consumed')" style="width: 140px">
        <template #body="{ data }: { data: AllocationResponse }">
          <span>{{ data.consumed_amount?.toLocaleString() ?? '0' }}</span>
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.rate')" style="width: 180px">
        <template #body="{ data }: { data: AllocationResponse }">
          <ConsumptionRateBadge :rate="consumptionRate(data)" />
        </template>
      </Column>
      <Column :header="t('shiftBudget.allocation.actions')" style="width: 180px">
        <template #body="{ data }: { data: AllocationResponse }">
          <div class="flex gap-1">
            <Button
              icon="pi pi-pencil"
              size="small"
              severity="secondary"
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              severity="danger"
              outlined
              @click="openDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <AllocationFormModal
      v-model:visible="formVisible"
      :allocation="editingAllocation"
      @submit-create="handleCreate"
      @submit-update="handleUpdate"
    />

    <Dialog
      v-model:visible="deleteConfirmVisible"
      :header="t('shiftBudget.allocation.delete')"
      :style="{ width: '400px' }"
      modal
    >
      <p v-if="deleteTarget" class="py-2">
        {{ t('shiftBudget.allocation.deleteConfirm', { id: deleteTarget.id }) }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="deleteConfirmVisible = false"
          />
          <Button
            :label="t('shiftBudget.allocation.delete')"
            severity="danger"
            @click="confirmDelete"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
