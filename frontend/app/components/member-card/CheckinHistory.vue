<script setup lang="ts">
import type { CheckinRecord } from '~/types/member-card'

const props = defineProps<{
  cardId: number
}>()

const memberCardApi = useMemberCardApi()
const notification = useNotification()
const { formatRelative } = useRelativeTime()

const checkins = ref<CheckinRecord[]>([])
const loading = ref(true)
const size = 20

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
  try {
    const res = await memberCardApi.getCheckins(props.cardId)
    checkins.value = res.data
  } catch {
    notification.error('チェックイン履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

const checkinTypeLabel = (type: string) => {
  return type === 'STAFF_SCAN' ? 'スタッフスキャン' : 'セルフ'
}

onMounted(loadCheckins)
</script>

<template>
  <div>
    <DataTable
      :value="checkins"
      :loading="loading"
      :paginator="true"
      :rows="size"
      data-key="id"
      striped-rows
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">チェックイン履歴がありません</div>
      </template>
      <Column header="日時">
        <template #body="{ data }">
          {{ formatRelative(data.checkedInAt) }}
        </template>
      </Column>
      <Column header="タイプ">
        <template #body="{ data }">
          <Badge :value="checkinTypeLabel(data.checkinType)" :severity="data.checkinType === 'SELF' ? 'info' : 'success'" />
        </template>
      </Column>
      <Column header="場所">
        <template #body="{ data }">
          {{ data.location ?? '-' }}
        </template>
      </Column>
      <Column header="対応スタッフ">
        <template #body="{ data }">
          {{ data.checkedInBy?.displayName ?? '-' }}
        </template>
      </Column>
    </DataTable>
  </div>
</template>
