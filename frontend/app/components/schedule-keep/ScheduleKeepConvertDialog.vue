<script setup lang="ts">
/**
 * F03.17 §4.5.3 — 候補日が無いキープの変換 UI。
 *
 * 「日付を選ぶだけで確定」の 2 タップ以内（ダイアログを開く＝1タップ、日付選択＝2タップ目）
 * を満たすため、確認ボタンを挟まずカレンダーの日付選択イベントで即 convert する。
 * 時刻指定は既定で畳んでおき（allDay=true が既定）、必要な人だけ開く（段階開示・§1.3）。
 */
import { toLocalDateString } from '~/utils/localDate'

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  select: [payload: { startAt: string; allDay: boolean }]
}>()

const showTimeInput = ref(false)
const timeValue = ref<Date | null>(null)

function onDateSelect(date: Date) {
  if (showTimeInput.value && timeValue.value) {
    const hh = String(timeValue.value.getHours()).padStart(2, '0')
    const mm = String(timeValue.value.getMinutes()).padStart(2, '0')
    emit('select', { startAt: `${toLocalDateString(date)}T${hh}:${mm}:00`, allDay: false })
  }
  else {
    emit('select', { startAt: `${toLocalDateString(date)}T00:00:00`, allDay: true })
  }
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('scheduleKeep.action.pickDate')"
    class="w-full max-w-sm"
    data-testid="schedule-keep-convert-dialog"
  >
    <DatePicker
      inline
      :model-value="null"
      data-testid="schedule-keep-convert-datepicker"
      @date-select="onDateSelect"
    />

    <div class="mt-3">
      <button
        type="button"
        class="text-sm text-primary-600 hover:underline dark:text-primary-400"
        data-testid="schedule-keep-convert-time-toggle"
        @click="showTimeInput = !showTimeInput"
      >
        {{ $t('scheduleKeep.action.specifyTime') }}
      </button>
      <DatePicker
        v-if="showTimeInput"
        v-model="timeValue"
        time-only
        show-icon
        class="mt-2 w-full"
        data-testid="schedule-keep-convert-time-input"
      />
    </div>
  </Dialog>
</template>
