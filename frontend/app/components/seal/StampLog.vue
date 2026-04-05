<script setup lang="ts">
import type { StampLog as StampLogType } from '~/types/seal'

const props = defineProps<{
  userId: number
}>()

const sealApi = useSealApi()
const notification = useNotification()

const logs = ref<StampLogType[]>([])
const loading = ref(true)
const nextCursor = ref<string | null>(null)

const variantLabel = (v: string) => {
  const map: Record<string, string> = { LAST_NAME: '姓', FULL_NAME: 'フルネーム', FIRST_NAME: '名' }
  return map[v] ?? v
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await sealApi.getStampLogs(props.userId, { size: 20 })
    logs.value = res.data
    nextCursor.value = res.meta.nextCursor
  } catch {
    notification.error('押印履歴の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  try {
    const res = await sealApi.getStampLogs(props.userId, { cursor: nextCursor.value, size: 20 })
    logs.value.push(...res.data)
    nextCursor.value = res.meta.nextCursor
  } catch {
    notification.error('追加データの取得に失敗しました')
  }
}

onMounted(loadLogs)
</script>

<template>
  <div>
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner />
    </div>

    <div v-else-if="logs.length === 0" class="py-8 text-center text-surface-500">
      押印履歴がありません
    </div>

    <div v-else>
      <DataTable :value="logs" data-key="stampId" striped-rows>
        <Column header="押印日時">
          <template #body="{ data }">
            {{ new Date(data.stampedAt).toLocaleString('ja-JP') }}
          </template>
        </Column>
        <Column header="バリアント">
          <template #body="{ data }">
            <Badge :value="variantLabel(data.variant)" severity="secondary" />
          </template>
        </Column>
        <Column header="対象">
          <template #body="{ data }">
            {{ data.targetTitle ?? `${data.targetType}#${data.targetId}` }}
          </template>
        </Column>
        <Column header="状態">
          <template #body="{ data }">
            <Badge v-if="data.isRevoked" value="取消済" severity="danger" />
            <Badge v-else value="有効" severity="success" />
          </template>
        </Column>
      </DataTable>

      <div v-if="nextCursor" class="mt-4 flex justify-center">
        <Button label="もっと見る" severity="secondary" outlined @click="loadMore" />
      </div>
    </div>
  </div>
</template>
