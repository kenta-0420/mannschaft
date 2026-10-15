<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('shift.autoAssign.button')"
    modal
    :style="{ width: '500px' }"
    :draggable="false"
  >
    <div class="space-y-6">
      <!-- 戦略選択 -->
      <div>
        <label class="block text-sm font-medium text-surface-700 mb-2">
          {{ $t('shift.autoAssign.strategy') }}
        </label>
        <div class="space-y-2">
          <div
            v-for="strategy in strategies"
            :key="strategy.value"
            class="flex items-center gap-3 p-3 rounded-lg border transition-colors"
            :class="
              selectedStrategy === strategy.value
                ? 'border-primary-500 bg-primary-50'
                : 'border-surface-200'
            "
          >
            <RadioButton
              v-model="selectedStrategy"
              :value="strategy.value"
              :disabled="strategy.disabled"
              :input-id="`strategy-${strategy.value}`"
            />
            <label
              :for="`strategy-${strategy.value}`"
              class="cursor-pointer flex-1"
              :class="strategy.disabled ? 'text-surface-400' : 'text-surface-700'"
            >
              {{ strategy.label }}
              <span v-if="strategy.disabled" class="ml-1 text-xs text-surface-400"
                >(準備中)</span
              >
            </label>
          </div>
        </div>
      </div>

      <!-- パラメータスライダー -->
      <div class="space-y-4">
        <div>
          <label class="flex justify-between text-sm text-surface-700 mb-1">
            <span>{{ $t('shift.autoAssign.preferenceWeight') }}</span>
            <span class="font-mono">{{ params.preferenceWeight?.toFixed(1) }}</span>
          </label>
          <Slider v-model="params.preferenceWeight" :min="0" :max="1" :step="0.1" class="w-full" />
        </div>
        <div>
          <label class="flex justify-between text-sm text-surface-700 mb-1">
            <span>{{ $t('shift.autoAssign.fairnessWeight') }}</span>
            <span class="font-mono">{{ params.fairnessWeight?.toFixed(1) }}</span>
          </label>
          <Slider v-model="params.fairnessWeight" :min="0" :max="1" :step="0.1" class="w-full" />
        </div>
        <div>
          <label class="flex justify-between text-sm text-surface-700 mb-1">
            <span>{{ $t('shift.autoAssign.consecutivePenalty') }}</span>
            <span class="font-mono">{{ params.consecutivePenaltyWeight?.toFixed(1) }}</span>
          </label>
          <Slider
            v-model="params.consecutivePenaltyWeight"
            :min="0"
            :max="1"
            :step="0.1"
            class="w-full"
          />
        </div>
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        text
        @click="visible = false"
      />
      <Button
        :label="isRunning ? $t('shift.autoAssign.running') : $t('shift.autoAssign.run')"
        :loading="isRunning"
        :disabled="isRunning"
        icon="pi pi-play"
        @click="onRun"
      />
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import type { AssignmentStrategyType, AssignmentParameters } from '~/types/shift'

interface Props {
  isRunning: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  run: [strategy: AssignmentStrategyType, params: AssignmentParameters]
}>()

const visible = defineModel<boolean>('visible', { default: false })

const { t } = useI18n()

const selectedStrategy = ref<AssignmentStrategyType>('GREEDY_V1')
const params = ref<AssignmentParameters>({
  preferenceWeight: 0.6,
  fairnessWeight: 0.3,
  consecutivePenaltyWeight: 0.1,
  respectWorkConstraints: true,
  overwriteExisting: false,
})

const strategies = computed(() => [
  { value: 'GREEDY_V1' as AssignmentStrategyType, label: t('shift.autoAssign.greedyV1'), disabled: false },
  { value: 'CSP_V1' as AssignmentStrategyType, label: 'CSP（制約プログラミング）', disabled: true },
])

function onRun(): void {
  emit('run', selectedStrategy.value, params.value)
}
</script>
