<script setup lang="ts">
import type { CursorPage, RanchRecord } from '~/types/ranch'

const { t } = useI18n()
useHead({ title: t('ranch.records.title') })
const privateRead = useRanchPrivateRead()
const { handleApiError } = useErrorHandler()
const page = ref<CursorPage<RanchRecord> | null>(null)
const loading = ref(true)
const failed = ref(false)
let loadRun = 0

async function load(cursor?: string) {
 const run = ++loadRun
 loading.value = true
 failed.value = false
 try {
  const result = await privateRead.read<CursorPage<RanchRecord>>('/api/v1/me/ranch/records', { cursor, limit: 20 })
  if (!privateRead.isCurrent() || run !== loadRun) return
  page.value = result
 } catch (error) {
  // 別本人への切替で破棄された取得は、新しい画面や通知へ作用させない。
  if (!privateRead.isCurrent() || run !== loadRun) return
  page.value = null
  failed.value = true
  handleApiError(error, 'RanchRecords')
 } finally {
  if (privateRead.isCurrent() && run === loadRun) loading.value = false
 }
}
onMounted(() => load())
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.records.title')" back-to="/my/ranch" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-else-if="failed" @retry="load()" />
  <SectionCard v-else-if="page">
   <DashboardEmptyState v-if="page.data.length === 0" :message="t('ranch.records.empty')" />
   <ul class="space-y-3">
    <li v-for="record in page.data" :key="record.id">
     {{ record.occurredAt }} · {{ t(`ranch.records.${record.kind}`) }} · {{ t('ranch.records.amounts', { points: record.deltaPoints, xp: record.deltaXp }) }}
     <NuxtLink v-if="record.sourceLink && record.sourceLink.url.startsWith('/') && !record.sourceLink.url.startsWith('//')" :to="record.sourceLink.url" class="flex min-h-11 items-center text-primary">{{ t('ranch.records.source') }}</NuxtLink>
    </li>
   </ul>
   <Button v-if="page.meta.hasNext && page.meta.nextCursor" class="min-h-11" :label="t('ranch.next')" @click="load(page.meta.nextCursor ?? undefined)" />
  </SectionCard>
 </div>
</template>
