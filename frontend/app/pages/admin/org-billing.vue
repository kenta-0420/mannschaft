<script setup lang="ts">
import type {
  OrgBillingSettingsResponse,
  OrgBillingOrganizationResponse,
  OrgBillingType,
} from '~/types/org-billing'

definePageMeta({ middleware: 'auth' })

const { getSettings, updateSettings, getOrganizations } = useOrgBillingApi()
const { success, error: showError } = useNotification()

const loading = ref(true)
const saving = ref<OrgBillingType | null>(null)
const settings = ref<OrgBillingSettingsResponse[]>([])
const organizations = ref<OrgBillingOrganizationResponse[]>([])
const totalRecords = ref(0)
const page = ref(0)
const rows = ref(20)

// 編集用フォーム（種別ごと）
const forms = ref<Record<OrgBillingType, { freeTeams: number; overageUnitPrice: number }>>({
  NONPROFIT: { freeTeams: 0, overageUnitPrice: 0 },
  FORPROFIT: { freeTeams: 0, overageUnitPrice: 0 },
})

function applySettingsToForms(list: OrgBillingSettingsResponse[]) {
  for (const s of list) {
    forms.value[s.orgType] = {
      freeTeams: s.freeTeams,
      overageUnitPrice: s.overageUnitPrice,
    }
  }
}

async function loadSettings() {
  try {
    const res = await getSettings()
    settings.value = res.data
    applySettingsToForms(res.data)
  } catch {
    showError('課金設定の取得に失敗しました')
  }
}

async function loadOrganizations() {
  try {
    const res = await getOrganizations({ page: page.value, size: rows.value })
    organizations.value = res.data
    totalRecords.value = res.meta?.total ?? res.data.length
  } catch {
    showError('組織一覧の取得に失敗しました')
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadSettings(), loadOrganizations()])
  } finally {
    loading.value = false
  }
}

async function saveSettings(orgType: OrgBillingType) {
  const form = forms.value[orgType]
  if (form.freeTeams < 0 || form.overageUnitPrice < 0) return
  saving.value = orgType
  try {
    await updateSettings(orgType, {
      freeTeams: form.freeTeams,
      overageUnitPrice: form.overageUnitPrice,
    })
    success('設定を保存しました')
    await loadSettings()
  } catch {
    showError('設定の保存に失敗しました')
  } finally {
    saving.value = null
  }
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  rows.value = event.rows
  loadOrganizations()
}

function orgTypeLabel(type: OrgBillingType): string {
  return type === 'NONPROFIT' ? '非営利' : '営利'
}

function billingStatusLabel(status: string): string {
  switch (status) {
    case 'FREE': return '無料枠内'
    case 'BILLABLE': return '課金中'
    case 'OVERDUE': return '未払い'
    default: return status
  }
}

function billingStatusSeverity(status: string): string {
  switch (status) {
    case 'FREE': return 'success'
    case 'BILLABLE': return 'info'
    case 'OVERDUE': return 'danger'
    default: return 'secondary'
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">組織数課金設定</h1>

    <Message severity="warn" :closable="false" class="mb-6">
      変更は翌月反映されます
    </Message>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 組織種別ごとの設定カード -->
      <div class="mb-8 grid grid-cols-1 gap-6 md:grid-cols-2">
        <div
          v-for="orgType in (['NONPROFIT', 'FORPROFIT'] as const)"
          :key="orgType"
          class="rounded-lg border border-surface-300 p-6"
        >
          <h2 class="mb-4 text-lg font-semibold">
            {{ orgTypeLabel(orgType) }}組織
          </h2>
          <div class="flex flex-col gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">
                無料枠チーム数
              </label>
              <InputNumber
                v-model="forms[orgType].freeTeams"
                :min="0"
                class="w-full"
                placeholder="例: 3"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">
                超過課金単価（円/チーム/月）
              </label>
              <InputNumber
                v-model="forms[orgType].overageUnitPrice"
                :min="0"
                class="w-full"
                placeholder="例: 500"
              />
            </div>
            <div class="flex justify-end">
              <Button
                label="保存"
                icon="pi pi-check"
                :loading="saving === orgType"
                @click="saveSettings(orgType)"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 組織一覧テーブル -->
      <h2 class="mb-4 text-lg font-semibold">組織一覧</h2>
      <DataTable
        :value="organizations"
        :lazy="true"
        :paginator="true"
        :rows="rows"
        :total-records="totalRecords"
        :first="page * rows"
        data-key="id"
        striped-rows
        @page="onPage"
      >
        <template #empty>
          <div class="py-12 text-center">
            <i class="pi pi-building mb-3 text-4xl text-surface-300" />
            <p class="text-surface-400">組織がありません</p>
          </div>
        </template>

        <Column field="name" header="組織名" />

        <Column header="種別" style="width: 100px">
          <template #body="{ data }">
            <Tag
              :value="orgTypeLabel(data.orgType)"
              :severity="data.orgType === 'NONPROFIT' ? 'info' : 'warn'"
            />
          </template>
        </Column>

        <Column header="チーム数" style="width: 120px">
          <template #body="{ data }">
            <span class="font-medium">{{ data.teamCount }}</span>
            <span class="ml-1 text-xs text-surface-500">
              (無料{{ data.freeTeams }} / 超過{{ data.overageTeams }})
            </span>
          </template>
        </Column>

        <Column header="月額課金" style="width: 140px">
          <template #body="{ data }">
            <span class="font-medium">{{ data.monthlyCharge.toLocaleString('ja-JP') }}円</span>
          </template>
        </Column>

        <Column header="課金ステータス" style="width: 120px">
          <template #body="{ data }">
            <Tag
              :value="billingStatusLabel(data.billingStatus)"
              :severity="billingStatusSeverity(data.billingStatus)"
            />
          </template>
        </Column>
      </DataTable>
    </template>
  </div>
</template>
