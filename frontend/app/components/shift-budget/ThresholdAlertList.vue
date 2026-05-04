<script setup lang="ts">
import type { AlertResponse } from '~/types/shiftBudget'

/**
 * F08.7 警告一覧コンポーネント（Phase 10-γ）。
 *
 * <p>承認応答ボタン付きの警告履歴を一覧表示する。
 * 設計書 §6.2.5 / §7.5。</p>
 *
 * <p>承認応答が必要な行は強調表示し、承認済の行は薄く表示する。</p>
 */
interface Props {
  alerts: AlertResponse[]
  /** 承認応答機能を有効化するか（BUDGET_ADMIN のみ true）*/
  canAcknowledge?: boolean
}

interface Emits {
  (e: 'acknowledge', alert: AlertResponse): void
}

withDefaults(defineProps<Props>(), {
  canAcknowledge: false,
})
const emit = defineEmits<Emits>()

const { t } = useI18n()

function formatDateTime(value: string | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString('ja-JP')
}

function thresholdSeverity(percent: number): 'warn' | 'danger' {
  if (percent >= 100) return 'danger'
  return 'warn'
}
</script>

<template>
  <DataTable :value="alerts" striped-rows data-key="id">
    <template #empty>
      <div class="py-8 text-center text-surface-500">{{ t('shiftBudget.alert.empty') }}</div>
    </template>
    <Column field="id" :header="t('shiftBudget.allocation.id')" style="width: 80px" />
    <Column field="allocation_id" header="Allocation ID" style="width: 120px" />
    <Column :header="t('shiftBudget.alert.thresholdPercent')" style="width: 120px">
      <template #body="{ data }: { data: AlertResponse }">
        <Tag
          :value="`${data.threshold_percent}%`"
          :severity="thresholdSeverity(data.threshold_percent)"
        />
      </template>
    </Column>
    <Column :header="t('shiftBudget.alert.triggeredAt')" style="width: 180px">
      <template #body="{ data }: { data: AlertResponse }">
        <span class="text-sm">{{ formatDateTime(data.triggered_at) }}</span>
      </template>
    </Column>
    <Column :header="t('shiftBudget.alert.consumedAtTrigger')" style="width: 140px">
      <template #body="{ data }: { data: AlertResponse }">
        <span class="text-sm">{{ data.consumed_amount_at_trigger?.toLocaleString() ?? '-' }}</span>
      </template>
    </Column>
    <Column :header="t('shiftBudget.alert.acknowledgedAt')" style="width: 200px">
      <template #body="{ data }: { data: AlertResponse }">
        <div v-if="data.acknowledged_at" class="text-sm">
          <Tag :value="t('shiftBudget.alert.acknowledged')" severity="success" class="mr-2" />
          <span>{{ formatDateTime(data.acknowledged_at) }}</span>
        </div>
        <Tag v-else :value="t('shiftBudget.alert.unacknowledged')" severity="warn" />
      </template>
    </Column>
    <Column :header="t('shiftBudget.allocation.actions')" style="width: 120px">
      <template #body="{ data }: { data: AlertResponse }">
        <Button
          v-if="canAcknowledge && !data.acknowledged_at"
          :label="t('shiftBudget.alert.acknowledge')"
          size="small"
          @click="emit('acknowledge', data)"
        />
      </template>
    </Column>
  </DataTable>
</template>
