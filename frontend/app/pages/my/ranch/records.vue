<script setup lang="ts">
import type { CursorPage, RanchRecord } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t } = useI18n(); useHead({ title: t('ranch.records.title') })
const api = useRanchApi(); const { handleApiError } = useErrorHandler(); const page = ref<CursorPage<RanchRecord> | null>(null); const loading = ref(false); const failed = ref(false)
async function load(cursor?: string) { loading.value = true; failed.value = false; try { page.value = await api.records(cursor) } catch(error) { page.value = null; failed.value = true; handleApiError(error, 'RanchRecords') } finally { loading.value = false } }
onMounted(() => load())
</script>
<template><div class="space-y-5"><PageHeader :title="t('ranch.records.title')" back-to="/my/ranch" /><PageLoading v-if="loading" /><DashboardErrorState v-else-if="failed" @retry="load()" /><SectionCard v-else-if="page"><DashboardEmptyState v-if="page.data.length === 0" :message="t('ranch.records.empty')" /><ul class="space-y-3"><li v-for="record in page.data" :key="record.id">{{ record.occurredAt }} · {{ t(`ranch.records.${record.kind}`) }} · {{ t('ranch.records.amounts', { points: record.deltaPoints, xp: record.deltaXp }) }}<NuxtLink v-if="record.sourceLink && record.sourceLink.url.startsWith('/') && !record.sourceLink.url.startsWith('//')" :to="record.sourceLink.url" class="flex min-h-11 items-center text-primary">{{ t('ranch.records.source') }}</NuxtLink></li></ul><Button v-if="page.meta.hasNext && page.meta.nextCursor" class="min-h-11" :label="t('ranch.next')" @click="load(page.meta.nextCursor!)" /></SectionCard></div></template>
