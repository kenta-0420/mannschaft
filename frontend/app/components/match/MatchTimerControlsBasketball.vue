<script setup lang="ts">
// F08.10 バスケ用タイマー操作ボタン行（sports/03_basketball.md §8.5）。
// 4 クォーター＋OT のピリオド進行ボタンを状態に応じて出し分ける。状態と経過は
// useMatchTimerBasketball が司り、本コンポは UI 操作のみを emit する（薄い表示層）。
import type { TimerState } from '~/composables/match/sport/useMatchTimerCore'

defineProps<{
  state: TimerState
  currentMinute: number | null
  running: boolean
}>()

const emit = defineEmits<{
  advance: []
  complete: []
  overtime: []
}>()

const { t } = useI18n()
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <Button
      v-if="state === 'WAITING'"
      :label="t('match.live.timer.start_quarter_1')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'QUARTER_1'"
      :label="t('match.live.timer.end_quarter_1')"
      severity="secondary"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'BREAK_1'"
      :label="t('match.live.timer.start_quarter_2')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'QUARTER_2'"
      :label="t('match.live.timer.end_quarter_2')"
      severity="secondary"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'HALF_TIME_BREAK'"
      :label="t('match.live.timer.start_quarter_3')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'QUARTER_3'"
      :label="t('match.live.timer.end_quarter_3')"
      severity="secondary"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'BREAK_3'"
      :label="t('match.live.timer.start_quarter_4')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <template v-else-if="state === 'QUARTER_4'">
      <!-- QUARTER_4 からの advance は次状態 COMPLETED → handleAdvance が completeMatch を呼ぶ -->
      <Button :label="t('match.live.timer.complete')" severity="secondary" @click="emit('advance')" />
      <Button :label="t('match.live.timer.to_overtime')" text @click="emit('overtime')" />
    </template>
    <template v-else-if="state === 'OVERTIME'">
      <!-- OVERTIME 終了 → handleAdvance が completeMatch を呼ぶ。なお同点なら再 OT。 -->
      <Button :label="t('match.live.timer.complete')" severity="secondary" @click="emit('advance')" />
      <Button :label="t('match.live.timer.to_overtime_again')" text @click="emit('overtime')" />
    </template>

    <Button
      v-if="running"
      :label="t('match.live.timer.minute_label') + ': ' + (currentMinute ?? 0)"
      text
      severity="secondary"
      disabled
    />
  </div>
</template>
