<script setup lang="ts">
import type { ProxyInputRecord } from '~/types/proxy-input'
import type { MemberResponse } from '~/types/member'
import type { PageMeta } from '~/types/api'

definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
const { formatDateTime } = useDatetime()
const scope = useProxyManagementScope()
const api = useProxyInputApi()
const subject = ref<MemberResponse | null>(null)
const records = ref<ProxyInputRecord[]>([])
const meta = ref<PageMeta>({ page: 0, size: 20, total: 0, totalPages: 0 })
const { page, rows, totalRecords, reset } = usePagination(20)
const loading = ref(false)
const failed = ref(false)
let request = 0
function scopeLabel(value: string) {
  const keys: Record<string, string> = {
    SURVEY: 'survey', SCHEDULE_ATTENDANCE: 'schedule_attendance', SHIFT_REQUEST: 'shift_request',
    ANNOUNCEMENT_READ: 'announcement_read', PARKING_APPLICATION: 'parking_application',
    CIRCULAR: 'circular', SUPPORTER_VIEW: 'supporter_view', PAYMENT: 'payment',
  }
  return keys[value] ? t(`proxy.scope.${keys[value]}`) : value
}
function sourceLabel(value: string) {
  const keys: Record<string, string> = {
    PAPER_FORM: 'paper_form', PHONE_INTERVIEW: 'phone_interview', IN_PERSON: 'in_person',
    GUARDIANSHIP_SWITCH: 'guardianship_switch',
  }
  return keys[value] ? t(`proxy.desk.inputSource.${keys[value]}`) : value
}

async function load() {
  const org = scope.organization.value
  const current = ++request
  records.value = []
  failed.value = false
  loading.value = true
  if (!org || !scope.allowed.value) { loading.value = false; return }
  try {
    const result = await api.getRecords({ organizationId: org.id, subjectUserId: subject.value?.userId, page: page.value, size: rows.value })
    if (current !== request) return
    records.value = result.data
    meta.value = result.meta
    totalRecords.value = result.meta.total
  }
  catch {
    if (current === request) failed.value = true
  }
  finally {
    if (current === request) loading.value = false
  }
}
watch(() => [scope.organization.value?.id, scope.allowed.value], () => {
  subject.value = null
  if (page.value !== 0) { reset(); return }
  void load()
})
watch(subject, () => {
  if (page.value !== 0) { reset(); return }
  void load()
})
watch(page, () => { void load() })
</script>

<template>
  <div class="mx-auto min-w-0 max-w-5xl space-y-4 p-4">
    <PageHeader :title="t('proxy.record.title')" class="flex-wrap [&>h1]:min-w-0 [&>h1]:max-w-full [&>h1]:break-words" />
    <nav class="flex flex-wrap gap-4">
      <NuxtLink to="/admin/proxy/consents" class="inline-flex min-h-11 items-center text-primary underline">{{ t('proxy.management.consentsTitle') }}</NuxtLink>
    </nav>
    <Select :model-value="scope.organization.value?.id" :options="scope.organizations.value" option-label="name" option-value="id" :placeholder="t('proxy.management.chooseOrganization')" :aria-label="t('proxy.management.chooseOrganization')" class="min-h-11 w-full" @update:model-value="scope.select" />
    <PageLoading v-if="scope.loading.value" role="status" :aria-label="t('proxy.management.loading')" class="!min-h-0 !pb-0 py-4" />
    <DashboardErrorState v-else-if="scope.failed.value" role="alert" :message="t('proxy.management.accessLoadFailed')" show-retry class="[&_button]:min-h-11" @retry="scope.load" />
    <p v-else-if="!scope.allowed.value" role="alert">{{ t('proxy.management.accessDenied') }}</p>
    <template v-else>
      <ProxyMemberPicker v-if="scope.organization.value" v-model="subject" :slug="scope.organization.value.slug" :label="t('proxy.management.subjectFilter')" />
      <Button :label="t('proxy.management.refresh')" class="min-h-11" :disabled="loading" @click="load" />
      <PageLoading v-if="loading" role="status" :aria-label="t('proxy.management.loading')" class="!min-h-0 !pb-0 py-4" />
      <DashboardErrorState v-else-if="failed" role="alert" :message="t('proxy.management.loadFailed')" show-retry class="[&_button]:min-h-11" @retry="load" />
      <DashboardEmptyState v-else-if="!records.length" :message="t('proxy.management.emptyRecords')" />
      <div v-else class="space-y-3">
        <article v-for="record in records" :key="record.id">
          <SectionCard class="space-y-2 break-words text-sm">
            <h2 class="font-semibold">{{ formatDateTime(record.createdAt) }}</h2>
            <dl class="grid gap-2 sm:grid-cols-2">
              <div><dt>{{ t('proxy.management.subject') }}</dt><dd>{{ record.subjectUserId === subject?.userId ? subject.displayName : `#${record.subjectUserId}` }}</dd></div>
              <div><dt>{{ t('proxy.management.proxy') }}</dt><dd>#{{ record.proxyUserId }}</dd></div>
              <div><dt>{{ t('proxy.consent.title') }}</dt><dd>{{ record.consentId == null ? '—' : `#${record.consentId}` }}</dd></div>
              <div><dt>{{ t('proxy.record.featureScope') }}</dt><dd>{{ scopeLabel(record.featureScope) }}</dd></div>
              <div><dt>{{ t('proxy.record.targetEntity') }}</dt><dd>{{ record.targetEntityType }} #{{ record.targetEntityId }}</dd></div>
              <div><dt>{{ t('proxy.desk.inputSource.label') }}</dt><dd>{{ sourceLabel(record.inputSource) }}</dd></div>
              <div><dt>{{ t('proxy.desk.originalStorage.label') }}</dt><dd>{{ record.originalStorageLocation || '—' }}</dd></div>
            </dl>
          </SectionCard>
        </article>
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <Button :label="t('proxy.management.previous')" outlined class="min-h-11" :disabled="page === 0 || loading" @click="page--" />
        <span>{{ t('proxy.management.page', { page: page + 1, pages: Math.max(meta.totalPages, 1), total: totalRecords }) }}</span>
        <Button :label="t('proxy.management.next')" outlined class="min-h-11" :disabled="page + 1 >= meta.totalPages || loading" @click="page++" />
      </div>
    </template>
  </div>
</template>
