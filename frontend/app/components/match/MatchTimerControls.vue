<script setup lang="ts">
// F08.10 タイマー操作ボタン行（04_frontend_and_ux.md §G.2 タイマー状態機械）。
// 状態に応じてピリオド進行ボタン（前半開始/ハーフタイム/後半/延長/PK/終了）を出し分ける。
// 状態と経過は useMatchTimer が司り、本コンポは UI 操作のみを emit する（薄い表示層）。
import type { TimerState } from '~/composables/match/useMatchTimer'

defineProps<{
  state: TimerState
  currentMinute: number | null
  running: boolean
}>()

const emit = defineEmits<{
  advance: []
  complete: []
  extra: []
  penalty: []
}>()

const { t } = useI18n()
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <Button
      v-if="state === 'WAITING'"
      :label="t('match.live.timer.start_first_half')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'FIRST_HALF'"
      :label="t('match.live.timer.to_half_time')"
      severity="secondary"
      @click="emit('advance')"
    />
    <Button
      v-else-if="state === 'HALF_TIME'"
      :label="t('match.live.timer.start_second_half')"
      icon="pi pi-play"
      @click="emit('advance')"
    />
    <template v-else-if="state === 'SECOND_HALF'">
      <Button :label="t('match.live.timer.complete')" severity="secondary" @click="emit('complete')" />
      <Button :label="t('match.live.timer.to_extra')" text @click="emit('extra')" />
    </template>
    <template v-else-if="state === 'EXTRA_FIRST' || state === 'EXTRA_SECOND'">
      <Button :label="t('match.live.timer.advance')" severity="secondary" @click="emit('advance')" />
      <Button :label="t('match.live.timer.to_penalty')" text @click="emit('penalty')" />
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
