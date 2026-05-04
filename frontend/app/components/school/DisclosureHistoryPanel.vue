<script setup lang="ts">
import { onMounted, watch } from 'vue'

const props = defineProps<{
  teamId: number
  evaluationId?: number
}>()

const { t } = useI18n()
const { disclosureHistory, loadingHistory, loadDisclosureHistory } = useAttendanceDisclosure()

async function fetchHistory(): Promise<void> {
  if (!props.evaluationId) return
  await loadDisclosureHistory(props.teamId, props.evaluationId)
}

onMounted(fetchHistory)

watch(() => props.evaluationId, fetchHistory)

function severityForDecision(decision: string): string {
  return decision === 'DISCLOSED' ? 'success' : 'warn'
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString()
}
</script>

<template>
  <div class="disclosure-history-panel" data-testid="disclosure-history-panel">
    <h3 class="text-base font-semibold mb-3">
      {{ $t('school.disclosure.historyTitle') }}
    </h3>

    <PageLoading v-if="loadingHistory" />

    <template v-else>
      <div
        v-if="disclosureHistory.length === 0"
        class="text-surface-400 text-sm py-4 text-center"
        data-testid="disclosure-history-empty"
      >
        {{ $t('school.disclosure.historyEmpty') }}
      </div>

      <ul v-else class="flex flex-col gap-3" data-testid="disclosure-history-list">
        <li
          v-for="entry in disclosureHistory"
          :key="entry.id"
          class="border border-surface-200 dark:border-surface-700 rounded-lg p-3"
          data-testid="disclosure-history-item"
        >
          <div class="flex items-center gap-2 mb-1">
            <Tag
              :value="$t(`school.disclosure.decision.${entry.decision}`)"
              :severity="severityForDecision(entry.decision)"
            />
            <span
              v-if="entry.mode"
              class="text-xs text-surface-500"
            >
              {{ $t(`school.disclosure.mode.${entry.mode}`) }}
            </span>
          </div>
          <div class="text-xs text-surface-400 mb-1">
            {{ formatDate(entry.decidedAt) }}
          </div>
          <p
            v-if="entry.message"
            class="text-sm text-surface-700 dark:text-surface-300 mt-1"
          >
            {{ entry.message }}
          </p>
        </li>
      </ul>
    </template>
  </div>
</template>
