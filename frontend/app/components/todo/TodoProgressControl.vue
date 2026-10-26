<script setup lang="ts">
const { t } = useI18n()

const props = defineProps<{
  progressRate: string
  progressManual: boolean
}>()

const emit = defineEmits<{
  'update:progressRate': [value: string]
  'update:progressManual': [value: boolean]
}>()

/** 進捗率を数値として扱うローカル状態 */
const localRate = ref(parseFloat(props.progressRate))
const showWarningModal = ref(false)

watch(
  () => props.progressRate,
  (val) => {
    localRate.value = parseFloat(val)
  },
)

function onSliderChange(event: Event) {
  const target = event.target as HTMLInputElement
  const val = Number(target.value)
  localRate.value = val
  emit('update:progressRate', val.toFixed(2))
}

function onNumberInput(event: Event) {
  const target = event.target as HTMLInputElement
  let val = Number(target.value)
  if (isNaN(val)) return
  val = Math.min(100, Math.max(0, val))
  localRate.value = val
  emit('update:progressRate', val.toFixed(2))
}

/** 自動算出への切り替えは警告モーダルを表示してから実行 */
function requestSwitchToAuto() {
  showWarningModal.value = true
}

function confirmSwitchToAuto() {
  showWarningModal.value = false
  emit('update:progressManual', false)
}

function cancelSwitchToAuto() {
  showWarningModal.value = false
}

function switchToManual() {
  emit('update:progressManual', true)
}
</script>

<template>
  <div class="space-y-3">
    <!-- モードバッジ -->
    <div class="flex items-center gap-2">
      <span
        class="rounded-full px-2 py-0.5 text-xs font-medium"
        :class="progressManual
          ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400'
          : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'"
      >
        {{ progressManual ? t('todo.enhancement.progress.manual_mode') : t('todo.enhancement.progress.auto_mode') }}
      </span>
    </div>

    <!-- スライダー + 数値入力（手動モード時のみ編集可） -->
    <div class="flex items-center gap-3">
      <input
        type="range"
        min="0"
        max="100"
        step="1"
        :value="localRate"
        :disabled="!progressManual"
        class="h-2 flex-1 cursor-pointer accent-primary disabled:cursor-not-allowed disabled:opacity-50"
        @input="onSliderChange"
      >
      <div class="flex items-center gap-1">
        <input
          type="number"
          min="0"
          max="100"
          step="1"
          :value="localRate"
          :disabled="!progressManual"
          class="w-16 rounded-lg border border-surface-300 bg-surface-0 px-2 py-1 text-right text-sm focus:outline-none focus:ring-2 focus:ring-primary disabled:cursor-not-allowed disabled:opacity-50 dark:border-surface-600 dark:bg-surface-800"
          @change="onNumberInput"
        >
        <span class="text-sm text-surface-500">%</span>
      </div>
    </div>

    <!-- モード切替ボタン -->
    <div class="flex gap-2">
      <button
        v-if="progressManual"
        type="button"
        class="rounded-lg border border-surface-300 px-3 py-1.5 text-xs text-surface-600 transition-colors hover:border-primary hover:text-primary dark:border-surface-600 dark:text-surface-400"
        @click="requestSwitchToAuto"
      >
        {{ t('todo.enhancement.progress.switch_to_auto') }}
      </button>
      <button
        v-else
        type="button"
        class="rounded-lg border border-surface-300 px-3 py-1.5 text-xs text-surface-600 transition-colors hover:border-primary hover:text-primary dark:border-surface-600 dark:text-surface-400"
        @click="switchToManual"
      >
        {{ t('todo.enhancement.progress.switch_to_manual') }}
      </button>
    </div>

    <!-- 自動算出切替警告モーダル -->
    <Teleport to="body">
      <div
        v-if="showWarningModal"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="cancelSwitchToAuto"
      >
        <div class="w-full max-w-sm rounded-xl bg-surface-0 p-6 shadow-xl dark:bg-surface-800">
          <h3 class="mb-3 text-base font-semibold text-surface-800 dark:text-surface-100">
            {{ t('todo.enhancement.progress.switch_to_auto') }}
          </h3>
          <p class="mb-5 text-sm text-surface-600 dark:text-surface-400">
            {{ t('todo.enhancement.progress.overwrite_warning') }}
          </p>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="rounded-lg border border-surface-300 px-4 py-2 text-sm text-surface-600 transition-colors hover:bg-surface-100 dark:border-surface-600 dark:text-surface-400 dark:hover:bg-surface-700"
              @click="cancelSwitchToAuto"
            >
              {{ t('button.cancel') }}
            </button>
            <button
              type="button"
              class="rounded-lg bg-primary px-4 py-2 text-sm text-white transition-colors hover:bg-primary/90"
              @click="confirmSwitchToAuto"
            >
              {{ t('button.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
