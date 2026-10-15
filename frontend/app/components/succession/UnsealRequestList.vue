<script setup lang="ts">
import type { UnsealRequestResponse, UnsealRequestStatus } from '~/types/succession'

const emit = defineEmits<{
  select: [id: string]
}>()

const props = defineProps<{
  orgId: string
}>()

const { t } = useI18n()
const { listRequests } = useUnsealRequestApi()
const { formatDateTime } = useDatetime()

const requests = ref<UnsealRequestResponse[]>([])
const loading = ref(false)

async function refresh() {
  loading.value = true
  try {
    const res = await listRequests(props.orgId)
    requests.value = res.data
  }
  finally {
    loading.value = false
  }
}

onMounted(refresh)
defineExpose({ refresh })

function statusSeverity(status: UnsealRequestStatus) {
  const map: Record<string, string> = {
    PENDING: 'warn',
    FIRST_APPROVED: 'info',
    UNSEALED: 'success',
    RE_SEALED: 'secondary',
    CANCELLED: 'danger',
  }
  return map[status] ?? 'secondary'
}

function shortId(id: string) {
  return id ? id.slice(0, 8) : '-'
}
</script>

<template>
  <DataTable :value="requests" :loading="loading" @row-click="emit('select', $event.data.id)">
    <Column :header="t('succession.unseal.field.id')" style="width: 100px">
      <template #body="{ data }">{{ shortId(data.id) }}</template>
    </Column>
    <Column :header="t('succession.unseal.field.preRegistrationId')" style="width: 100px">
      <template #body="{ data }">{{ shortId(data.preRegistrationId) }}</template>
    </Column>
    <Column field="requestedBy" :header="t('succession.unseal.field.requestedBy')" />
    <Column :header="t('succession.unseal.field.status')">
      <template #body="{ data }">
        <Tag :severity="statusSeverity(data.status)" :value="t(`succession.unseal.status.${data.status}`)" />
      </template>
    </Column>
    <Column field="firstApproverUserId" :header="t('succession.unseal.field.firstApprover')" />
    <Column field="secondApproverUserId" :header="t('succession.unseal.field.secondApprover')" />
    <Column :header="t('succession.unseal.field.autoResealAt')">
      <template #body="{ data }">{{ data.autoResealAt ? formatDateTime(data.autoResealAt) : '-' }}</template>
    </Column>
    <Column :header="t('succession.unseal.field.createdAt')">
      <template #body="{ data }">{{ formatDateTime(data.createdAt) }}</template>
    </Column>
  </DataTable>
</template>
