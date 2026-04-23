<script setup lang="ts">
import type { ShiftPreference } from '~/types/shift'

/**
 * F03.5 シフト希望一括設定ダイアログ
 *
 * 選択した preference を全スロット・平日のみ・休日のみに一括適用できる。
 */

interface Props {
  visible: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  apply: [payload: { preference: ShiftPreference; target: 'all' | 'weekday' | 'weekend' }]
}>()

const { t } = useI18n()

const selectedPreference = ref<ShiftPreference>('AVAILABLE')
const selectedTarget = ref<'all' | 'weekday' | 'weekend'>('all')

const targetOptions = computed(() => [
  { label: t('shift.bulkSet.applyToAll'), value: 'all' as const },
  { label: t('shift.bulkSet.applyToWeekday'), value: 'weekday' as const },
  { label: t('shift.bulkSet.applyToWeekend'), value: 'weekend' as const },
])

function close() {
  emit('update:visible', false)
}

function apply() {
  emit('apply', {
    preference: selectedPreference.value,
    target: selectedTarget.value,
  })
  close()
}
</script>

<template>
  <Dialog
    :visible="props.visible"
    :header="t('shift.bulkSet.title')"
    modal
    class="w-full max-w-sm"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <!-- 希望選択 -->
      <div>
        <label class="mb-2 block text-sm font-medium text-surface-700">
          {{ t('shift.field.preference') }}
        </label>
        <ShiftPreferenceRadioCard v-model="selectedPreference" />
      </div>

      <!-- 適用範囲 -->
      <div>
        <label class="mb-2 block text-sm font-medium text-surface-700">
          {{ t('shift.bulkSet.title') }}
        </label>
        <div class="flex flex-col gap-2">
          <label
            v-for="opt in targetOptions"
            :key="opt.value"
            class="flex min-h-[44px] cursor-pointer items-center gap-2 rounded-lg border border-surface-200 px-3 py-2 hover:bg-surface-50"
          >
            <RadioButton v-model="selectedTarget" :value="opt.value" :input-id="`bulk-target-${opt.value}`" />
            <span class="text-sm">{{ opt.label }}</span>
          </label>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="t('common.button.cancel')" text severity="secondary" @click="close" />
        <Button :label="t('shift.bulkSet.confirm')" @click="apply" />
      </div>
    </template>
  </Dialog>
</template>
