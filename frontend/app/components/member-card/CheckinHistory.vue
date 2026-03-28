<script setup lang="ts">
import type { CheckinRecord } from '~/types/member-card'

const props = defineProps<{
  cardId: number
}>()

const memberCardApi = useMemberCardApi()
const notification = useNotification()
const { formatRelative } = useRelativeTime()

const checkins = ref<CheckinRecord[]>([])
const totalElements = ref(0)
const loading = ref(true)
const page = ref(0)
const size = 20

async function loadCheckins() {
  loading.value = true
  try {
    const res = await memberCardApi.getCheckins(props.cardId, { page: page.value, size })
    checkins.value = res.data
    totalElements.value = res.meta.totalElements
  } catch {
    notification.error('チェックイン履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function onPage(event: { page: number }) {
  page.value = event.page
  loadCheckins()
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
      :lazy="true"
      :paginator="true"
      :rows="size"
      :total-records="totalElements"
      :first="page * size"
      data-key="id"
      striped-rows
      @page="onPage"
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
