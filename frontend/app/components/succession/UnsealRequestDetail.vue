<script setup lang="ts">
import type { UnsealRequestResponse, UnsealRequestStatus } from '~/types/succession'

defineProps<{
  orgId: string
  request: UnsealRequestResponse
}>()

const emit = defineEmits<{
  refresh: []
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()
const dialogVisible = ref(false)
const dialogMode = ref<'FIRST_APPROVE' | 'SECOND_APPROVE' | 'CANCEL'>('FIRST_APPROVE')

function openDialog(mode: 'FIRST_APPROVE' | 'SECOND_APPROVE' | 'CANCEL') {
  dialogMode.value = mode
  dialogVisible.value = true
}

function onDone() {
  emit('refresh')
}

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
</script>

<template>
  <div class="flex flex-col gap-4 p-4">
    <div class="flex items-center gap-2">
      <Tag :severity="statusSeverity(request.status)" :value="t(`succession.unseal.status.${request.status}`)" />
    </div>

    <div class="grid grid-cols-2 gap-3 text-sm">
      <div class="text-surface-500">{{ t('succession.unseal.field.id') }}</div>
      <div class="font-mono text-xs break-all">{{ request.id }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.preRegistrationId') }}</div>
      <div class="font-mono text-xs break-all">{{ request.preRegistrationId }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.requestedBy') }}</div>
      <div>{{ request.requestedBy }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.reason') }}</div>
      <div>{{ request.requestReason }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.firstApprover') }}</div>
      <div>{{ request.firstApproverUserId ?? '-' }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.secondApprover') }}</div>
      <div>{{ request.secondApproverUserId ?? '-' }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.autoResealAt') }}</div>
      <div>{{ request.autoResealAt ? formatDateTime(request.autoResealAt) : '-' }}</div>

      <div class="text-surface-500">{{ t('succession.unseal.field.createdAt') }}</div>
      <div>{{ formatDateTime(request.createdAt) }}</div>
    </div>

    <div v-if="request.status === 'PENDING' || request.status === 'FIRST_APPROVED'" class="flex gap-2 pt-2 border-t border-surface-200 dark:border-surface-700">
      <Button
        v-if="request.status === 'PENDING'"
        :label="t('succession.unseal.action.firstApprove')"
        severity="primary"
        @click="openDialog('FIRST_APPROVE')"
      />
      <Button
        v-if="request.status === 'FIRST_APPROVED'"
        :label="t('succession.unseal.action.secondApprove')"
        severity="success"
        @click="openDialog('SECOND_APPROVE')"
      />
      <Button
        :label="t('succession.unseal.action.cancel')"
        severity="danger"
        outlined
        @click="openDialog('CANCEL')"
      />
    </div>

    <UnsealApprovalDialog
      v-model:visible="dialogVisible"
      :org-id="orgId"
      :request="request"
      :mode="dialogMode"
      @done="onDone"
    />
  </div>
</template>
