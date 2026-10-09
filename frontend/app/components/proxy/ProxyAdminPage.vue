<script setup lang="ts">
import dayjs from 'dayjs'
import { useConfirm } from 'primevue/useconfirm'
import type { ProxyInputConsent } from '~/types/proxy-input'
import { proxyConsentState } from '~/utils/proxyConsentState'

const props = defineProps<{ mode: 'consents' | 'records' }>()
const { t } = useI18n()
const { formatDate, formatDateTime } = useDatetime()
const authStore = useAuthStore()
const proxyDeskStore = useProxyDeskStore()
onMounted(() => {
  proxyDeskStore.restoreFromStorage()
})
const confirm = useConfirm()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const proxyApi = useProxyInputApi()
const {
  organizations,
  organizationSlug,
  loading,
  error,
  consents,
  records,
  pagination,
  loadPage,
  changeOrganization,
  changePage,
  mayApprove,
  mayRevoke,
} = useProxyAdmin(props.mode)
const { page, rows, totalRecords } = pagination
const busy = ref(false)
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  confirm.close()
})
const title = computed(() =>
  t(props.mode === 'consents' ? 'proxy.admin.consentsTitle' : 'proxy.record.title'),
)

function stateLabel(consent: ProxyInputConsent): string {
  return t(
    `proxy.consent.status.${proxyConsentState(consent, dayjs().tz('Asia/Tokyo').format('YYYY-MM-DD'))}`,
  )
}

function requestAction(consent: ProxyInputConsent, action: 'approve' | 'revoke') {
  if (disposed || busy.value || loading.value || error.value !== undefined) return
  if (action === 'approve' ? !mayApprove(consent) : !mayRevoke(consent)) return
  confirm.require({
    group: 'proxy-admin-confirm',
    header: t(action === 'approve' ? 'proxy.admin.approve' : 'proxy.revoke.title'),
    message: t(action === 'approve' ? 'proxy.admin.approveConfirm' : 'proxy.revoke.confirm', {
      id: consent.id,
    }),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t(action === 'approve' ? 'proxy.admin.approve' : 'proxy.admin.revoke'),
    rejectLabel: t('button.cancel'),
    accept: () => {
      void executeAction(consent, action)
    },
  })
}

async function executeAction(consent: ProxyInputConsent, action: 'approve' | 'revoke') {
  // ダイアログ待機中の組合変更や二重クリックでも、選択中組合以外へ送信しない。
  if (disposed || busy.value || loading.value || error.value !== undefined) return
  if (action === 'approve' ? !mayApprove(consent) : !mayRevoke(consent)) return
  busy.value = true
  try {
    if (action === 'approve') {
      await proxyApi.approveConsent(consent.id)
    } else {
      const userId = authStore.user?.id
      if (userId === undefined) return
      await proxyApi.revokeConsent(
        consent.id,
        consent.subjectUserId === userId
          ? { revokeMethod: 'API_BY_SUBJECT' }
          : { revokeMethod: 'PAPER_BY_SUBJECT' },
      )
    }
    // 失効した同意をヘッダーへ残さず、再取得前に既存の永続化状態も解除する。
    if (action === 'revoke' && proxyDeskStore.pinnedConsentId === consent.id) proxyDeskStore.unpin()
    if (disposed) return
    notification.success(t(action === 'approve' ? 'proxy.admin.approved' : 'proxy.admin.revoked'))
    await loadPage()
  } catch (cause) {
    if (disposed) return
    handleApiError(cause, '代理入力同意書管理操作')
    // 409等の状態競合後も最新状態を再取得し、古い操作を繰り返させない。
    await loadPage()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="min-w-0 space-y-4" :data-testid="`proxy-admin-${mode}`">
    <ConfirmDialog
      group="proxy-admin-confirm"
      :pt="{
        pcCloseButton: { root: { class: 'min-h-11 min-w-11' } },
        pcAcceptButton: { root: { class: 'min-h-11 min-w-11' } },
        pcRejectButton: { root: { class: 'min-h-11 min-w-11' } },
      }"
    />
    <PageHeader :title="title" />
    <p class="text-surface-600 dark:text-surface-300">{{ t('proxy.admin.description') }}</p>
    <div class="flex flex-wrap gap-3">
      <NuxtLink
        to="/admin/proxy/consents"
        class="flex min-h-11 items-center text-primary underline"
        >{{ t('proxy.admin.consentsTitle') }}</NuxtLink
      >
      <NuxtLink
        to="/admin/proxy/records"
        class="flex min-h-11 items-center text-primary underline"
        >{{ t('proxy.record.title') }}</NuxtLink
      >
    </div>
    <SectionCard>
      <div v-if="organizations.length" class="mb-4 flex min-w-0 flex-col gap-2">
        <label for="proxy-organization">{{ t('proxy.admin.organization') }}</label>
        <Select
          input-id="proxy-organization"
          :model-value="organizationSlug"
          :options="organizations"
          option-label="name"
          option-value="slug"
          :disabled="loading || busy"
          class="w-full text-base md:max-w-sm"
          :pt="{
            root: { style: { minHeight: '44px', minWidth: '44px' } },
            dropdown: { style: { minHeight: '44px', minWidth: '44px' } },
          }"
          data-testid="proxy-organization"
          @update:model-value="changeOrganization"
        />
      </div>
      <PageLoading v-if="loading" />
      <DashboardErrorState v-else-if="error !== undefined" :error="error" @retry="loadPage" />
      <template v-else>
        <DashboardEmptyState
          v-if="(mode === 'consents' ? consents : records).length === 0"
          :message="t(mode === 'consents' ? 'proxy.admin.noConsents' : 'proxy.admin.noRecords')"
        />
        <div v-else class="min-w-0 overflow-x-auto">
          <DataTable
            v-if="mode === 'consents'"
            :value="consents"
            data-key="id"
            data-testid="proxy-consents-table"
            table-class="whitespace-nowrap"
          >
            <Column field="id" :header="t('proxy.consent.title')" />
            <Column field="subjectUserId" :header="t('proxy.consent.subjectUserId')" />
            <Column field="proxyUserId" :header="t('proxy.admin.proxyUserId')" />
            <Column :header="t('proxy.admin.status')">
              <template #body="{ data }"><Tag :value="stateLabel(data)" /></template>
            </Column>
            <Column :header="t('proxy.consent.method.label')">
              <template #body="{ data }">{{
                t(`proxy.consent.method.${data.consentMethod.toLowerCase()}`)
              }}</template>
            </Column>
            <Column :header="t('proxy.consent.effectiveFrom')">
              <template #body="{ data }">{{ formatDate(data.effectiveFrom) }}</template>
            </Column>
            <Column :header="t('proxy.consent.effectiveUntil')">
              <template #body="{ data }">{{ formatDate(data.effectiveUntil) }}</template>
            </Column>
            <Column :header="t('proxy.consent.scopes')">
              <template #body="{ data }"
                ><div class="flex flex-wrap gap-1">
                  <Tag
                    v-for="scope in data.scopes"
                    :key="scope"
                    :value="t(`proxy.scope.${scope.toLowerCase()}`)"
                  /></div
              ></template>
            </Column>
            <Column :header="t('proxy.consent.approvedAt')">
              <template #body="{ data }">{{
                formatDateTime(data.approvedAt) || t('proxy.admin.none')
              }}</template>
            </Column>
            <Column :header="t('proxy.consent.revokedAt')">
              <template #body="{ data }">{{
                formatDateTime(data.revokedAt) || t('proxy.admin.none')
              }}</template>
            </Column>
            <Column :header="t('proxy.admin.actions')">
              <template #body="{ data }">
                <div class="flex gap-2">
                  <Button
                    v-if="data.status === 'PENDING_APPROVAL' && !data.revokedAt"
                    :label="t('proxy.admin.approve')"
                    :disabled="busy || !mayApprove(data)"
                    :title="
                      data.proxyUserId === authStore.user?.id
                        ? t('proxy.admin.selfApprovalDenied')
                        : undefined
                    "
                    class="min-h-11 whitespace-nowrap"
                    :data-testid="`proxy-approve-${data.id}`"
                    @click="requestAction(data, 'approve')"
                  />
                  <Button
                    v-if="!data.revokedAt"
                    :label="t('proxy.admin.revoke')"
                    :disabled="busy || !mayRevoke(data)"
                    severity="danger"
                    outlined
                    class="min-h-11 whitespace-nowrap"
                    :data-testid="`proxy-revoke-${data.id}`"
                    @click="requestAction(data, 'revoke')"
                  />
                </div>
              </template>
            </Column>
          </DataTable>
          <DataTable
            v-else
            :value="records"
            data-key="id"
            data-testid="proxy-records-table"
            table-class="whitespace-nowrap"
          >
            <Column field="id" :header="t('proxy.admin.recordId')" />
            <Column field="proxyInputConsentId" :header="t('proxy.consent.title')" />
            <Column field="subjectUserId" :header="t('proxy.consent.subjectUserId')" />
            <Column field="proxyUserId" :header="t('proxy.admin.proxyUserId')" />
            <Column :header="t('proxy.record.featureScope')">
              <template #body="{ data }">{{
                t(`proxy.scope.${data.featureScope.toLowerCase()}`)
              }}</template>
            </Column>
            <Column field="targetEntityType" :header="t('proxy.admin.targetType')" />
            <Column field="targetEntityId" :header="t('proxy.record.targetEntity')" />
            <Column :header="t('proxy.desk.inputSource.label')">
              <template #body="{ data }">{{
                t(`proxy.desk.inputSource.${data.inputSource.toLowerCase()}`)
              }}</template>
            </Column>
            <Column :header="t('proxy.desk.originalStorage.label')">
              <template #body="{ data }">{{
                data.originalStorageLocation || t('proxy.admin.none')
              }}</template>
            </Column>
            <Column :header="t('proxy.record.createdAt')">
              <template #body="{ data }">{{ formatDateTime(data.createdAt) }}</template>
            </Column>
          </DataTable>
        </div>
        <Paginator
          v-if="totalRecords > 0"
          :first="page * rows"
          :rows="rows"
          :total-records="totalRecords"
          :rows-per-page-options="[20, 50, 100]"
          :pt="{
            first: { style: { minHeight: '44px', minWidth: '44px' } },
            prev: { style: { minHeight: '44px', minWidth: '44px' } },
            page: { style: { minHeight: '44px', minWidth: '44px' } },
            next: { style: { minHeight: '44px', minWidth: '44px' } },
            last: { style: { minHeight: '44px', minWidth: '44px' } },
            pcRowPerPageDropdown: {
              root: { style: { minHeight: '44px', minWidth: '44px' } },
              dropdown: { style: { minHeight: '44px', minWidth: '44px' } },
            },
          }"
          :class="{ 'pointer-events-none opacity-50': busy }"
          :aria-busy="busy"
          data-testid="proxy-admin-pagination"
          @page="changePage"
        />
      </template>
    </SectionCard>
  </div>
</template>
