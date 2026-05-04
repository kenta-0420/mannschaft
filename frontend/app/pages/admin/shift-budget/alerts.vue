<script setup lang="ts">
import type { AlertResponse } from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 警告履歴一覧 (`/admin/shift-budget/alerts`)。
 *
 * <p>設計書 §6.2.5 / §7.5 に準拠。未承認警告を上に表示し、承認応答ボタンを提供する。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>BUDGET_VIEW: 一覧閲覧</li>
 *   <li>BUDGET_ADMIN: 承認応答（バックエンドが権限不足時 403）</li>
 * </ul>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const alerts = ref<AlertResponse[]>([])
const loading = ref(false)
const page = ref(0)
const pageSize = ref(20)

const ackDialogVisible = ref(false)
const ackTarget = ref<AlertResponse | null>(null)
const ackComment = ref('')

async function load() {
  if (!organizationId.value) {
    alerts.value = []
    return
  }
  loading.value = true
  try {
    alerts.value = await api.listAlerts(organizationId.value, page.value, pageSize.value)
  }
  catch {
    notification.error(t('shiftBudget.alert.loadError'))
  }
  finally {
    loading.value = false
  }
}

function openAcknowledge(alert: AlertResponse) {
  ackTarget.value = alert
  ackComment.value = ''
  ackDialogVisible.value = true
}

async function confirmAcknowledge() {
  if (!ackTarget.value || !organizationId.value) return
  try {
    await api.acknowledgeAlert(organizationId.value, ackTarget.value.id, {
      comment: ackComment.value || null,
    })
    notification.success(t('shiftBudget.alert.acknowledgeSuccess'))
    ackDialogVisible.value = false
    ackTarget.value = null
    await load()
  }
  catch {
    notification.error(t('shiftBudget.alert.acknowledgeError'))
  }
}

watch(organizationId, () => load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <PageHeader :title="t('shiftBudget.alert.title')" />

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <ThresholdAlertList v-else :alerts="alerts" :can-acknowledge="true" @acknowledge="openAcknowledge" />

    <Dialog
      v-model:visible="ackDialogVisible"
      :header="t('shiftBudget.alert.acknowledge')"
      :style="{ width: '500px' }"
      modal
    >
      <div v-if="ackTarget" class="flex flex-col gap-4">
        <Message severity="info" :closable="false">
          {{ t('shiftBudget.alert.acknowledgeConfirm', { id: ackTarget.id }) }}
        </Message>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.alert.comment') }}</label>
          <Textarea
            v-model="ackComment"
            rows="3"
            class="w-full"
            :placeholder="t('shiftBudget.alert.commentPlaceholder')"
            :maxlength="500"
          />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            @click="ackDialogVisible = false"
          />
          <Button :label="t('shiftBudget.alert.acknowledge')" @click="confirmAcknowledge" />
        </div>
      </template>
    </Dialog>
  </div>
</template>
