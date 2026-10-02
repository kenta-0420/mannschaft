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
const page = ref(0)
const loading = ref(false)
const failed = ref(false)
let request = 0

async function load() {
  const org = scope.organization.value
  const current = ++request
  records.value = []
  failed.value = false
  loading.value = true
  if (!org || !scope.allowed.value) { loading.value = false; return }
  try {
    const result = await api.getRecords({ organizationId: org.id, subjectUserId: subject.value?.userId, page: page.value, size: 20 })
    if (current !== request) return
    records.value = result.data
    meta.value = result.meta
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
  if (page.value !== 0) { page.value = 0; return }
  void load()
})
watch(subject, () => {
  if (page.value !== 0) { page.value = 0; return }
  void load()
})
watch(page, () => { void load() })
</script>

<template>
  <div class="mx-auto min-w-0 max-w-5xl space-y-4 p-4">
    <h1 class="text-2xl font-bold">{{ t('proxy.record.title') }}</h1>
    <nav class="flex flex-wrap gap-4">
      <NuxtLink to="/admin/proxy/consents" class="inline-flex min-h-11 items-center text-primary underline">{{ t('proxy.management.consentsTitle') }}</NuxtLink>
    </nav>
    <Select :model-value="scope.organization.value?.id" :options="scope.organizations.value" option-label="name" option-value="id" :placeholder="t('proxy.management.chooseOrganization')" :aria-label="t('proxy.management.chooseOrganization')" class="min-h-11 w-full" @update:model-value="scope.select" />
    <p v-if="scope.loading.value" role="status">{{ t('proxy.management.loading') }}</p>
    <div v-else-if="scope.failed.value" role="alert">
      <p>{{ t('proxy.management.accessLoadFailed') }}</p>
      <Button :label="t('proxy.management.retry')" class="mt-2 min-h-11" @click="scope.load" />
    </div>
    <p v-else-if="!scope.allowed.value" role="alert">{{ t('proxy.management.accessDenied') }}</p>
    <template v-else>
      <ProxyMemberPicker v-if="scope.organization.value" v-model="subject" :slug="scope.organization.value.slug" :label="t('proxy.management.subjectFilter')" />
      <Button :label="t('proxy.management.refresh')" class="min-h-11" :disabled="loading" @click="load" />
      <p v-if="loading" role="status">{{ t('proxy.management.loading') }}</p>
      <div v-else-if="failed" role="alert">
        <p>{{ t('proxy.management.loadFailed') }}</p>
        <Button :label="t('proxy.management.retry')" class="mt-2 min-h-11" @click="load" />
      </div>
      <p v-else-if="!records.length">{{ t('proxy.management.emptyRecords') }}</p>
      <div v-else class="space-y-3">
        <article v-for="record in records" :key="record.id" class="space-y-2 break-words rounded-xl border border-surface-200 p-4 text-sm dark:border-surface-700">
          <h2 class="font-semibold">{{ formatDateTime(record.createdAt) }}</h2>
          <dl class="grid gap-2 sm:grid-cols-2">
            <div><dt>{{ t('proxy.management.subject') }}</dt><dd>{{ record.subjectUserId === subject?.userId ? subject.displayName : `#${record.subjectUserId}` }}</dd></div>
            <div><dt>{{ t('proxy.management.proxy') }}</dt><dd>#{{ record.proxyUserId }}</dd></div>
            <div><dt>{{ t('proxy.consent.title') }}</dt><dd>{{ record.consentId == null ? '—' : `#${record.consentId}` }}</dd></div>
            <div><dt>{{ t('proxy.record.featureScope') }}</dt><dd>{{ record.featureScope }}</dd></div>
            <div><dt>{{ t('proxy.record.targetEntity') }}</dt><dd>{{ record.targetEntityType }} #{{ record.targetEntityId }}</dd></div>
            <div><dt>{{ t('proxy.desk.inputSource.label') }}</dt><dd>{{ record.inputSource }}</dd></div>
            <div><dt>{{ t('proxy.desk.originalStorage.label') }}</dt><dd>{{ record.originalStorageLocation || '—' }}</dd></div>
          </dl>
        </article>
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <Button :label="t('proxy.management.previous')" outlined class="min-h-11" :disabled="page === 0 || loading" @click="page--" />
        <span>{{ t('proxy.management.page', { page: page + 1, pages: Math.max(meta.totalPages, 1), total: meta.total }) }}</span>
        <Button :label="t('proxy.management.next')" outlined class="min-h-11" :disabled="page + 1 >= meta.totalPages || loading" @click="page++" />
      </div>
    </template>
  </div>
</template>
