<script setup lang="ts">
import type { ProxyInputConsent } from '~/types/proxy-input'
import type { MemberResponse } from '~/types/member'
import type { PageMeta } from '~/types/api'

definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
const { formatDate, formatDateTime } = useDatetime()
const auth = useAuthStore()
const scope = useProxyManagementScope()
const api = useProxyInputApi()
const consents = ref<ProxyInputConsent[]>([])
const meta = ref<PageMeta>({ page: 0, size: 20, total: 0, totalPages: 0 })
const { page, rows, totalRecords, reset } = usePagination(20)
const loading = ref(false)
const loadFailed = ref(false)
const mutationFailed = ref(false)
const reloadFailed = ref(false)
const success = ref(false)
const busy = ref(false)
const revokeTarget = ref<ProxyInputConsent | null>(null)
const approveTarget = ref<ProxyInputConsent | null>(null)
const witness = ref<MemberResponse | null>(null)
const reason = ref('')
const paper = ref(true)
let request = 0
function scopeLabel(value: string) {
  const keys: Record<string, string> = {
    SURVEY: 'survey', SCHEDULE_ATTENDANCE: 'schedule_attendance', SHIFT_REQUEST: 'shift_request',
    ANNOUNCEMENT_READ: 'announcement_read', PARKING_APPLICATION: 'parking_application',
    CIRCULAR: 'circular', SUPPORTER_VIEW: 'supporter_view', PAYMENT: 'payment',
  }
  return keys[value] ? t(`proxy.scope.${keys[value]}`) : value
}

async function load() {
  const org = scope.organization.value
  const current = ++request
  consents.value = []
  loadFailed.value = false
  loading.value = true
  if (!org || !scope.allowed.value) { loading.value = false; return false }
  try {
    const result = await api.getConsentsByOrg(String(org.id), page.value, rows.value)
    if (current !== request) return false
    consents.value = result.data
    meta.value = result.meta
    totalRecords.value = result.meta.total
    reloadFailed.value = false
    return true
  }
  catch {
    if (current === request) loadFailed.value = true
    return false
  }
  finally {
    if (current === request) loading.value = false
  }
}

function canApprove(consent: ProxyInputConsent) {
  return consent.status === 'PENDING_APPROVAL' && consent.proxyUserId !== auth.user?.id
    && scope.permissions.value.includes('PROXY_CONSENT_APPROVE')
}

async function mutate(action: () => Promise<unknown>) {
  const organizationId = scope.organization.value?.id
  busy.value = true
  mutationFailed.value = false
  success.value = false
  reloadFailed.value = false
  try {
    await action()
    if (scope.organization.value?.id !== organizationId || !scope.allowed.value) return
    success.value = true
    approveTarget.value = null
    revokeTarget.value = null
    reloadFailed.value = !(await load())
  }
  catch {
    if (scope.organization.value?.id === organizationId && scope.allowed.value) mutationFailed.value = true
  }
  finally {
    busy.value = false
  }
}

function openRevoke(consent: ProxyInputConsent) {
  revokeTarget.value = consent
  witness.value = null
  reason.value = ''
  paper.value = consent.subjectUserId !== auth.user?.id
  mutationFailed.value = false
}

function approve() {
  const target = approveTarget.value
  if (!target || !canApprove(target)) return
  void mutate(() => api.approveConsent(target.id))
}

function revoke() {
  const target = revokeTarget.value
  if (!target || reason.value.length > 255 || (paper.value && !witness.value)) return
  void mutate(() => api.revokeConsent(target.id, {
    revokeMethod: paper.value ? 'PAPER_BY_SUBJECT' : 'API_BY_SUBJECT',
    revokeReason: reason.value || undefined,
    ...(paper.value ? { revokeWitnessedByUserId: witness.value!.userId } : {}),
  }))
}

watch(() => [scope.organization.value?.id, scope.allowed.value], () => {
  revokeTarget.value = null
  approveTarget.value = null
  success.value = false
  mutationFailed.value = false
  reloadFailed.value = false
  if (page.value !== 0) { reset(); return }
  void load()
})
watch(page, () => { void load() })
</script>

<template>
  <div class="mx-auto min-w-0 max-w-5xl space-y-4 p-4">
    <PageHeader :title="t('proxy.management.consentsTitle')" class="flex-wrap [&>h1]:min-w-0 [&>h1]:max-w-full [&>h1]:break-words" />
    <nav class="flex flex-wrap gap-4">
      <NuxtLink to="/admin/proxy/records" class="inline-flex min-h-11 items-center text-primary underline">{{ t('proxy.record.title') }}</NuxtLink>
    </nav>
    <Select :model-value="scope.organization.value?.id" :options="scope.organizations.value" option-label="name" option-value="id" :placeholder="t('proxy.management.chooseOrganization')" :aria-label="t('proxy.management.chooseOrganization')" class="min-h-11 w-full" @update:model-value="scope.select" />
    <PageLoading v-if="scope.loading.value" role="status" :aria-label="t('proxy.management.loading')" class="!min-h-0 !pb-0 py-4" />
    <DashboardErrorState v-else-if="scope.failed.value" role="alert" :message="t('proxy.management.accessLoadFailed')" show-retry class="[&_button]:min-h-11" @retry="scope.load" />
    <p v-else-if="!scope.allowed.value" role="alert">{{ t('proxy.management.accessDenied') }}</p>
    <template v-else>
      <p v-if="success" role="status" class="text-green-700">{{ t('proxy.management.saved') }}</p>
      <p v-if="mutationFailed" role="alert" class="text-red-700">{{ t('proxy.management.mutationFailed') }}</p>
      <p v-if="reloadFailed" role="alert" class="text-red-700">{{ t('proxy.management.reloadFailed') }}</p>
      <Button :label="t('proxy.management.refresh')" class="min-h-11" :disabled="busy || loading" @click="load" />
      <PageLoading v-if="loading" role="status" :aria-label="t('proxy.management.loading')" class="!min-h-0 !pb-0 py-4" />
      <DashboardErrorState v-else-if="loadFailed" role="alert" :message="t('proxy.management.loadFailed')" show-retry class="[&_button]:min-h-11" @retry="load" />
      <DashboardEmptyState v-else-if="!consents.length" :message="t('proxy.management.emptyConsents')" />
      <div v-else class="space-y-3">
        <article v-for="consent in consents" :key="consent.id">
          <SectionCard class="space-y-3 break-words">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <h2 class="font-semibold">{{ t('proxy.consent.title') }} #{{ consent.id }}</h2>
              <Tag :value="t(`proxy.management.status.${consent.status}`)" />
            </div>
            <dl class="grid gap-2 text-sm sm:grid-cols-2">
              <div><dt>{{ t('proxy.management.subject') }}</dt><dd>#{{ consent.subjectUserId }}</dd></div>
              <div><dt>{{ t('proxy.management.proxy') }}</dt><dd>#{{ consent.proxyUserId }}</dd></div>
              <div><dt>{{ t('proxy.consent.effectiveFrom') }}</dt><dd>{{ formatDate(consent.effectiveFrom) }}</dd></div>
              <div><dt>{{ t('proxy.consent.effectiveUntil') }}</dt><dd>{{ formatDate(consent.effectiveUntil) }}</dd></div>
              <div><dt>{{ t('proxy.consent.approvedAt') }}</dt><dd>{{ formatDateTime(consent.approvedAt) }}</dd></div>
              <div><dt>{{ t('proxy.consent.revokedAt') }}</dt><dd>{{ formatDateTime(consent.revokedAt) }}</dd></div>
              <div><dt>{{ t('proxy.revoke.reason') }}</dt><dd>{{ consent.revokeReason || '—' }}</dd></div>
              <div><dt>{{ t('proxy.management.witness') }}</dt><dd>{{ consent.revokeWitnessedByUserId == null ? '—' : `#${consent.revokeWitnessedByUserId}` }}</dd></div>
              <div><dt>{{ t('proxy.management.revokeMethod') }}</dt><dd>{{ consent.revokeMethod ? t(`proxy.management.methods.${consent.revokeMethod}`, consent.revokeMethod) : '—' }}</dd></div>
              <div><dt>{{ t('proxy.consent.scopes') }}</dt><dd>{{ consent.scopes.map(scopeLabel).join(', ') }}</dd></div>
            </dl>
            <div class="flex flex-wrap gap-2">
              <Button v-if="canApprove(consent)" :label="t('proxy.management.approve')" class="min-h-11" :disabled="busy" @click="approveTarget = consent; mutationFailed = false" />
              <Button v-if="consent.status !== 'REVOKED'" :label="t('proxy.revoke.title')" severity="danger" outlined class="min-h-11" :disabled="busy" @click="openRevoke(consent)" />
            </div>
          </SectionCard>
        </article>
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <Button :label="t('proxy.management.previous')" outlined class="min-h-11" :disabled="page === 0 || loading || busy" @click="page--" />
        <span>{{ t('proxy.management.page', { page: page + 1, pages: Math.max(meta.totalPages, 1), total: totalRecords }) }}</span>
        <Button :label="t('proxy.management.next')" outlined class="min-h-11" :disabled="page + 1 >= meta.totalPages || loading || busy" @click="page++" />
      </div>
    </template>
    <Dialog :visible="!!approveTarget" modal :header="t('proxy.management.approve')" class="mx-3 w-full max-w-lg" :closable="!busy" @update:visible="value => { if (!value && !busy) approveTarget = null }">
      <div class="space-y-4">
        <p>{{ t('proxy.management.approveConfirm') }}</p>
        <p v-if="approveTarget">{{ t('proxy.consent.title') }} #{{ approveTarget.id }}</p>
        <p v-if="mutationFailed" role="alert" class="text-red-700">{{ t('proxy.management.mutationFailed') }}</p>
        <div class="flex flex-wrap gap-2">
          <Button :label="t('proxy.management.cancel')" outlined class="min-h-11" :disabled="busy" @click="approveTarget = null" />
          <Button :label="t('proxy.management.approve')" class="min-h-11" :loading="busy" :disabled="busy" @click="approve" />
        </div>
      </div>
    </Dialog>
    <Dialog :visible="!!revokeTarget" modal :header="t('proxy.revoke.title')" class="mx-3 w-full max-w-lg" :closable="!busy" @update:visible="value => { if (!value && !busy) revokeTarget = null }">
      <div v-if="revokeTarget && scope.organization.value" class="space-y-4">
        <p>{{ t('proxy.revoke.confirm') }}</p>
        <label v-if="revokeTarget.subjectUserId === auth.user?.id" class="flex items-center gap-2">
          <Checkbox v-model="paper" binary />{{ t('proxy.revoke.method.paper') }}
        </label>
        <ProxyMemberPicker v-if="paper" v-model="witness" :slug="scope.organization.value.slug" admin-only :label="t('proxy.management.witness')" />
        <label class="block space-y-1">
          <span>{{ t('proxy.revoke.reason') }}</span>
          <Textarea v-model="reason" rows="3" class="w-full" :maxlength="255" />
          <span class="block text-sm">{{ reason.length }} / 255</span>
        </label>
        <p v-if="mutationFailed" role="alert" class="text-red-700">{{ t('proxy.management.mutationFailed') }}</p>
        <div class="flex flex-wrap gap-2">
          <Button :label="t('proxy.management.cancel')" outlined class="min-h-11" :disabled="busy" @click="revokeTarget = null" />
          <Button :label="t('proxy.revoke.title')" severity="danger" class="min-h-11" :loading="busy" :disabled="busy || (paper && !witness) || reason.length > 255" @click="revoke" />
        </div>
      </div>
    </Dialog>
  </div>
</template>
