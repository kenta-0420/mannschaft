<script setup lang="ts">
import type { ConfirmableNotificationRecipientItem, ConfirmableRecipientViewerRole } from '~/types/confirmable'
const props = defineProps<{ notificationId: number; scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string }>()
const { getRecipients, resendReminder } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const { page, rows, totalRecords, onPage } = usePagination(50)
const recipients = ref<ConfirmableNotificationRecipientItem[]>([])
const viewerRole = ref<ConfirmableRecipientViewerRole>('MEMBER')
const confirmedCount = ref(0)
const unconfirmedCount = ref(0)
const loading = ref(false)
const resending = ref(false)
const unconfirmedOnly = ref(false)
const isPrivileged = computed(() => viewerRole.value === 'ADMIN' || viewerRole.value === 'CREATOR')
async function loadRecipients() {
  loading.value = true
  try {
    const response = await getRecipients(props.scopeType, props.scopeId, props.notificationId, { page: page.value, size: rows.value, unconfirmedOnly: unconfirmedOnly.value })
    recipients.value = response.data.items
    totalRecords.value = response.data.totalElements
    confirmedCount.value = response.data.confirmedCount
    unconfirmedCount.value = response.data.unconfirmedCount
    viewerRole.value = response.data.viewerRole
  } catch (error) { handleApiError(error, 'confirmable recipients') } finally { loading.value = false }
}
async function changePage(event: { page: number; rows: number }) { onPage(event); await loadRecipients() }
async function onResendReminder() { resending.value = true; try { await resendReminder(props.scopeType, props.scopeId, props.notificationId) } catch (error) { handleApiError(error, 'confirmable reminder') } finally { resending.value = false } }
watch(unconfirmedOnly, () => { page.value = 0; loadRecipients() })
onMounted(loadRecipients)
</script>
<template>
  <div>
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <div class="flex gap-3 text-sm"><span>{{ $t('confirmable.confirmed') }}: {{ confirmedCount }}</span><span>{{ $t('confirmable.unconfirmed_list') }}: {{ unconfirmedCount }}</span></div>
      <div class="flex items-center gap-2"><Checkbox v-model="unconfirmedOnly" binary input-id="unconfirmed-only" /><label for="unconfirmed-only">{{ $t('confirmable.unconfirmed_only') }}</label><Button v-if="isPrivileged && unconfirmedCount > 0" :label="$t('confirmable.resend_reminder')" size="small" :loading="resending" @click="onResendReminder" /></div>
    </div>
    <PageLoading v-if="loading" size="32px" />
    <DataTable v-else :value="recipients" paginator lazy :rows="rows" :first="page * rows" :total-records="totalRecords" @page="changePage">
      <Column :header="$t('confirmable.recipient')"><template #body="{ data }: { data: ConfirmableNotificationRecipientItem }"><div class="flex items-center gap-2"><Avatar v-if="data.avatarUrl" :image="data.avatarUrl" shape="circle" /><span>{{ data.withdrawn ? $t('confirmable.withdrawn_user') : (data.displayName ?? $t('confirmable.unknown_recipient')) }}</span></div></template></Column>
      <Column :header="$t('confirmable.confirmed')"><template #body="{ data }: { data: ConfirmableNotificationRecipientItem }"><Tag :value="data.withdrawn ? $t('confirmable.withdrawn') : data.isConfirmed ? $t('confirmable.already_confirmed') : $t('confirmable.unconfirmed')" :severity="data.withdrawn ? 'secondary' : data.isConfirmed ? 'success' : 'warn'" /></template></Column>
      <Column v-if="isPrivileged" field="confirmedAt" :header="$t('confirmable.confirmed_at')" />
      <Column v-if="isPrivileged" :header="$t('confirmable.confirmed_via_label')"><template #body="{ data }: { data: ConfirmableNotificationRecipientItem }">{{ data.confirmedVia ? $t(`confirmable.confirmed_via.${data.confirmedVia}`) : '—' }}</template></Column>
    </DataTable>
  </div>
</template>
