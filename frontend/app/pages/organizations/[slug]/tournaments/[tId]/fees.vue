<script setup lang="ts">
import type { components } from '~/types/generated/index'
import type { PaymentItemResponse } from '~/types/payment'

type TournamentFeeResponse = components['schemas']['TournamentFeeResponse']
type CreateTournamentFeeRequest = components['schemas']['CreateTournamentFeeRequest']

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const tId = Number(route.params.tId)

const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)
const { listFees, createFee, deleteFee } = useTournamentFee(orgSlug, tId)
const { getPaymentItems } = usePaymentApi()
const notification = useNotification()

// ===== 一覧 =====
const fees = ref<TournamentFeeResponse[]>([])
const loading = ref(true)

async function loadFees() {
  loading.value = true
  try {
    fees.value = await listFees()
  } catch {
    notification.error(t('tournament.fees.error_load'))
  } finally {
    loading.value = false
  }
}

// ===== 支払い項目 =====
const paymentItems = ref<PaymentItemResponse[]>([])

async function loadPaymentItems() {
  try {
    const res = await getPaymentItems('organization', orgSlug)
    paymentItems.value = res.data
  } catch {
    // 支払い項目は補助情報のためサイレント
  }
}

// ===== 作成ダイアログ =====
const showCreateDialog = ref(false)
const createForm = ref<CreateTournamentFeeRequest>({
  paymentItemId: 0,
  title: '',
  divisionId: undefined,
  targetScope: 'ALL',
  paymentDue: undefined,
  teamIds: [],
})
const creating = ref(false)

function openCreateDialog() {
  createForm.value = {
    paymentItemId: 0,
    title: '',
    divisionId: undefined,
    targetScope: 'ALL',
    paymentDue: undefined,
    teamIds: [],
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!createForm.value.paymentItemId) return
  creating.value = true
  try {
    const created = await createFee(createForm.value)
    fees.value.unshift(created)
    showCreateDialog.value = false
    notification.success(t('tournament.fees.create_success'))
  } catch {
    notification.error(t('tournament.fees.error_create'))
  } finally {
    creating.value = false
  }
}

// ===== 削除ダイアログ =====
const deleteTarget = ref<TournamentFeeResponse | null>(null)
const showDeleteDialog = ref(false)
const deleting = ref(false)

function openDeleteDialog(fee: TournamentFeeResponse) {
  deleteTarget.value = fee
  showDeleteDialog.value = true
}

async function handleDelete() {
  if (!deleteTarget.value?.id) return
  deleting.value = true
  try {
    await deleteFee(deleteTarget.value.id)
    fees.value = fees.value.filter(f => f.id !== deleteTarget.value!.id)
    showDeleteDialog.value = false
    deleteTarget.value = null
    notification.success(t('tournament.fees.delete_success'))
  } catch {
    notification.error(t('tournament.fees.error_delete'))
  } finally {
    deleting.value = false
  }
}

// ===== ヘルパー =====
function formatAmount(fee: TournamentFeeResponse): string {
  if (fee.amount == null) return '-'
  const currency = fee.currency ?? 'JPY'
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency }).format(fee.amount)
}

function formatDueDate(fee: TournamentFeeResponse): string {
  if (!fee.paymentDue) return '-'
  return new Date(fee.paymentDue).toLocaleDateString('ja-JP')
}

function targetLabel(fee: TournamentFeeResponse): string {
  return fee.targetScope === 'ALL' ? t('tournament.fees.target_all') : t('tournament.fees.target_specific')
}

function paymentItemName(itemId: number | undefined): string {
  if (itemId == null) return '-'
  return paymentItems.value.find(p => p.id === itemId)?.meta.name ?? String(itemId)
}

onMounted(async () => {
  await loadPermissions()
  await Promise.all([loadFees(), loadPaymentItems()])
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/organizations/${orgSlug}/tournaments/${tId}`" :label="$t('tournament.fees.title')" />
    </div>

    <div class="mb-6 flex items-center justify-between">
      <PageHeader :title="$t('tournament.fees.title')" />
      <Button
        v-if="isAdminOrDeputy"
        :label="$t('tournament.fees.create')"
        icon="pi pi-plus"
        @click="openCreateDialog"
      />
    </div>

    <PageLoading v-if="loading" size="40px" />
    <template v-else>
      <DashboardEmptyState
        v-if="fees.length === 0"
        icon="pi pi-money-bill"
        :message="$t('tournament.fees.empty')"
      />
      <DataTable v-else :value="fees" class="text-sm" striped-rows>
        <Column :header="$t('tournament.fees.payment_item')">
          <template #body="{ data }">
            {{ paymentItemName(data.paymentItemId) }}
          </template>
        </Column>
        <Column :header="$t('tournament.fees.amount')">
          <template #body="{ data }">
            {{ formatAmount(data) }}
          </template>
        </Column>
        <Column :header="$t('tournament.fees.due_date')">
          <template #body="{ data }">
            {{ formatDueDate(data) }}
          </template>
        </Column>
        <Column :header="$t('tournament.fees.target_all')">
          <template #body="{ data }">
            <span class="rounded bg-surface-100 px-2 py-0.5 text-xs">
              {{ targetLabel(data) }}
            </span>
          </template>
        </Column>
        <Column v-if="isAdminOrDeputy" header="" style="width: 6rem">
          <template #body="{ data }">
            <Button
              :label="$t('tournament.fees.delete')"
              icon="pi pi-trash"
              severity="danger"
              text
              size="small"
              @click="openDeleteDialog(data)"
            />
          </template>
        </Column>
      </DataTable>
    </template>

    <!-- 参加費作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('tournament.fees.create')"
      modal
      :style="{ width: '420px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('tournament.fees.payment_item') }}</label>
          <Select
            v-model="createForm.paymentItemId"
            :options="paymentItems"
            option-label="meta.name"
            option-value="id"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('tournament.fees.amount') }}</label>
          <InputText
            v-model="createForm.title"
            class="w-full"
            :placeholder="$t('tournament.fees.title')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('tournament.fees.due_date') }}</label>
          <InputText
            v-model="createForm.paymentDue"
            type="datetime-local"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('tournament.fees.target_all') }}</label>
          <Select
            v-model="createForm.targetScope"
            :options="[
              { label: $t('tournament.fees.target_all'), value: 'ALL' },
              { label: $t('tournament.fees.target_specific'), value: 'SPECIFIC' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          text
          @click="showCreateDialog = false"
        />
        <Button
          :label="$t('tournament.fees.create')"
          :loading="creating"
          :disabled="!createForm.paymentItemId"
          @click="handleCreate"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      :header="$t('tournament.fees.delete')"
      modal
      :style="{ width: '360px' }"
    >
      <p class="text-sm">{{ $t('tournament.fees.delete_confirm') }}</p>
      <template #footer>
        <Button
          :label="$t('common.cancel')"
          text
          @click="showDeleteDialog = false"
        />
        <Button
          :label="$t('tournament.fees.delete')"
          severity="danger"
          :loading="deleting"
          @click="handleDelete"
        />
      </template>
    </Dialog>
  </div>
</template>
