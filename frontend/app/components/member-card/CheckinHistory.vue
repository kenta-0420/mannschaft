<script setup lang="ts">
import type { CheckinHistoryRecord } from '~/types/member-card'

const props = defineProps<{
  cardId: number
}>()

const memberCardApi = useMemberCardApi()
const loadError = shallowRef<unknown>(null)
const { formatRelative } = useRelativeTime()

const checkins = ref<CheckinHistoryRecord[]>([])
const loading = ref(true)
const size = 20
const { t } = useI18n()

/**
 * チェックイン履歴を読み込む。
 *
 * BE はこの API をページングしない（全件を一度に返す）ため、
 * ページングは DataTable のクライアントサイド分割に任せる。
 * かつては lazy ページャーに BE が送らない総件数を渡しており、
 * 常に 0 になってページャーが出なかった（CMP-260912-1823）。
 */
async function loadCheckins() {
  loading.value = true
  loadError.value = null
  try {
    const res = await memberCardApi.getCheckins(props.cardId)
    checkins.value = res.data
  } catch (error) {
    loadError.value = error
  } finally {
    loading.value = false
  }
}

const checkinTypeLabel = (type: string) => {
  return t(type === 'STAFF_SCAN' ? 'memberCardAdmin.historyColumns.scan' : 'memberCardAdmin.historyColumns.self')
}

watch(() => props.cardId, loadCheckins)
onMounted(loadCheckins)
</script>

<template>
  <div>
    <DashboardErrorState v-if="loadError" :error="loadError" @retry="loadCheckins" />
    <!-- 0件のときは表を描かない。狭幅で DataTable が横スクロール幅になり、表幅中央の空メッセージが画面外へ切れるため -->
    <DashboardEmptyState
      v-else-if="!loading && checkins.length === 0"
      data-testid="checkin-history-empty"
      icon="pi pi-history"
      :message="$t('memberCardAdmin.historyColumns.empty')"
    />
    <DataTable
      v-else
      :value="checkins"
      :loading="loading"
      :paginator="true"
      :rows="size"
      data-key="id"
      striped-rows
    >
      <Column :header="$t('memberCardAdmin.historyColumns.time')">
        <template #body="{ data }">
          {{ formatRelative(data.checkedInAt) }}
        </template>
      </Column>
      <Column :header="$t('memberCardAdmin.historyColumns.type')">
        <template #body="{ data }">
          <Badge :value="checkinTypeLabel(data.checkinType)" :severity="data.checkinType === 'SELF' ? 'info' : 'success'" />
        </template>
      </Column>
      <Column :header="$t('memberCardAdmin.historyColumns.location')">
        <template #body="{ data }">
          {{ data.location ?? '-' }}
        </template>
      </Column>
      <Column :header="$t('memberCardAdmin.historyColumns.staff')">
        <template #body="{ data }">
          {{ data.checkedInByName ?? '-' }}
        </template>
      </Column>
    </DataTable>
  </div>
</template>
