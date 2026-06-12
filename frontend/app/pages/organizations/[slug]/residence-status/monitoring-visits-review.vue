<script setup lang="ts">
// TODO: i18n キーへの移行が必要
import type { ContactResult, MonitoringVisitResponse } from '~/types/residenceStatus'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { listVisitsByCommittee, listVisitsByResident } = useMonitoringVisitApi()
const { formatDate } = useDatetime()

// フィルターモード: 'committee' | 'resident'
type FilterMode = 'committee' | 'resident'
const filterMode = ref<FilterMode>('committee')

const committeeIdInput = ref<string>('')
const residentRegistryIdInput = ref<string>('')

const visits = ref<MonitoringVisitResponse[]>([])
const loading = ref(false)
const searched = ref(false)

// ContactResult の Tag severity（dashboard.vue と同じロジック）
function contactResultSeverity(result: ContactResult): string {
  const map: Record<ContactResult, string> = {
    MET: 'success',
    NO_RESPONSE: 'warning',
    MAILBOX_ABNORMAL: 'danger',
    METER_ABNORMAL: 'danger',
    NEIGHBOR_INFO: 'info',
    REFUSED: 'secondary',
    OTHER: 'secondary',
  }
  return map[result] ?? 'secondary'
}

// ContactResult の表示ラベル（dashboard.vue と同じロジック）
function contactResultLabel(result: ContactResult): string {
  const map: Record<ContactResult, string> = {
    MET: '対面確認',
    NO_RESPONSE: '応答なし',
    MAILBOX_ABNORMAL: 'ポスト異常',
    METER_ABNORMAL: 'メーター異常',
    NEIGHBOR_INFO: '近隣情報',
    REFUSED: '拒否',
    OTHER: 'その他',
  }
  return map[result] ?? result
}


// メモの先頭20文字
function truncateMemo(memo: string | null): string {
  if (!memo) return '-'
  return memo.length > 20 ? memo.slice(0, 20) + '...' : memo
}

// 検索実行
async function handleSearch() {
  loading.value = true
  searched.value = true
  try {
    if (filterMode.value === 'committee') {
      if (!committeeIdInput.value) return
      const res = await listVisitsByCommittee(orgSlug.value, Number(committeeIdInput.value))
      visits.value = res.data
    }
    else {
      if (!residentRegistryIdInput.value) return
      const res = await listVisitsByResident(orgSlug.value, Number(residentRegistryIdInput.value))
      visits.value = res.data
    }
  }
  catch (e) {
    console.error('訪問履歴取得エラー:', e)
    visits.value = []
  }
  finally {
    loading.value = false
  }
}

// フィルターモード切替時にリセット
function handleFilterModeChange(mode: FilterMode) {
  filterMode.value = mode
  committeeIdInput.value = ''
  residentRegistryIdInput.value = ''
  visits.value = []
  searched.value = false
}

// 検索ボタンの有効条件
const canSearch = computed(() => {
  if (filterMode.value === 'committee') return !!committeeIdInput.value
  return !!residentRegistryIdInput.value
})

function handleBackToDashboard() {
  navigateTo(`/organizations/${orgSlug.value}/residence-status/dashboard`)
}
</script>

<template>
  <div class="flex flex-col gap-6 p-6">
    <!-- TODO: i18n -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-4">
        <Button
          icon="pi pi-arrow-left"
          text
          severity="secondary"
          @click="handleBackToDashboard"
        />
        <h1 class="text-2xl font-bold">
          訪問履歴レビュー（管理者）
        </h1>
      </div>
    </div>

    <!-- フィルターパネル -->
    <div class="border rounded p-4 bg-white dark:bg-surface-800 flex flex-col gap-4">
      <!-- モード切替 -->
      <div class="flex items-center gap-3">
        <span class="text-sm font-medium">検索モード:</span>
        <div class="flex gap-2">
          <Button
            label="委員会ID"
            :severity="filterMode === 'committee' ? 'primary' : 'secondary'"
            size="small"
            :outlined="filterMode !== 'committee'"
            @click="handleFilterModeChange('committee')"
          />
          <Button
            label="居住者ID"
            :severity="filterMode === 'resident' ? 'primary' : 'secondary'"
            size="small"
            :outlined="filterMode !== 'resident'"
            @click="handleFilterModeChange('resident')"
          />
        </div>
      </div>

      <!-- 委員会ID検索 -->
      <div
        v-if="filterMode === 'committee'"
        class="flex items-center gap-3"
      >
        <label class="text-sm font-medium whitespace-nowrap">委員会ID</label>
        <InputText
          v-model="committeeIdInput"
          type="number"
          placeholder="委員会IDを入力"
          style="width: 14rem"
          @keydown.enter="canSearch && handleSearch()"
        />
        <Button
          label="検索"
          icon="pi pi-search"
          :disabled="!canSearch"
          :loading="loading"
          @click="handleSearch"
        />
      </div>

      <!-- 居住者ID検索 -->
      <div
        v-if="filterMode === 'resident'"
        class="flex items-center gap-3"
      >
        <label class="text-sm font-medium whitespace-nowrap">居住者台帳ID</label>
        <InputText
          v-model="residentRegistryIdInput"
          type="number"
          placeholder="居住者台帳IDを入力"
          style="width: 14rem"
          @keydown.enter="canSearch && handleSearch()"
        />
        <Button
          label="検索"
          icon="pi pi-search"
          :disabled="!canSearch"
          :loading="loading"
          @click="handleSearch"
        />
      </div>
    </div>

    <!-- ローディング -->
    <div
      v-if="loading"
      class="flex items-center justify-center py-12"
    >
      <LoadingBounce />
    </div>

    <!-- 一覧テーブル -->
    <DataTable
      v-else
      :value="visits"
      class="w-full"
    >
      <template #empty>
        <div class="text-center py-8 text-gray-400">
          <span v-if="searched">該当する訪問記録がありません</span>
          <span v-else>検索条件を入力して「検索」ボタンを押してください</span>
        </div>
      </template>

      <Column
        field="visitedAt"
        header="訪問日時"
        style="width: 10rem"
      >
        <template #body="{ data }">
          {{ formatDate(data.visitedAt) }}
        </template>
      </Column>

      <Column
        field="contactResult"
        header="接触結果"
        style="width: 10rem"
      >
        <template #body="{ data }">
          <Tag
            :value="contactResultLabel(data.contactResult)"
            :severity="contactResultSeverity(data.contactResult)"
          />
        </template>
      </Column>

      <Column
        field="considerationMemo"
        header="メモ（先頭20文字）"
      >
        <template #body="{ data }">
          {{ truncateMemo(data.considerationMemo) }}
        </template>
      </Column>

      <Column
        field="nextVisitRecommendedAt"
        header="次回推奨訪問日"
        style="width: 10rem"
      >
        <template #body="{ data }">
          {{ formatDate(data.nextVisitRecommendedAt) }}
        </template>
      </Column>

      <Column
        field="committeeId"
        header="委員会ID"
        style="width: 8rem"
      />

      <Column
        field="residentRegistryId"
        header="居住者台帳ID"
        style="width: 8rem"
      />
    </DataTable>
  </div>
</template>
