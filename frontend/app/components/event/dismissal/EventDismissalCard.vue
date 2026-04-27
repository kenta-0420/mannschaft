<script setup lang="ts">
/**
 * F03.12 §16 ダッシュボード警告カード。
 *
 * <p>主催者向けの「解散通知が未送信」リマインドが届いた時に、ダッシュボードに表示する警告カード。
 * CTA「解散通知を送る」ボタンで {@link DismissalDialog} を開く。
 *
 * <p>{@code DashboardWidgetCard} は colSpan/refresh 等の高機能カードのため、ここでは
 * シンプルな自前カードでよい（任務指示の「シンプルな card」方針に従う）。</p>
 */
import DismissalDialog from './DismissalDialog.vue'

defineProps<{
  eventId: number
  eventName: string
  teamId: number
}>()

const emit = defineEmits<{
  submitted: []
}>()

const dialogOpen = ref(false)

function openDialog() {
  dialogOpen.value = true
}

function onSubmitted() {
  dialogOpen.value = false
  emit('submitted')
}
</script>

<template>
  <div
    class="flex items-start gap-3 rounded-lg border border-amber-300 bg-amber-50 p-4 dark:border-amber-700 dark:bg-amber-950"
    data-testid="event-dismissal-card"
  >
    <i class="pi pi-exclamation-triangle mt-0.5 text-xl text-amber-600 dark:text-amber-300" />
    <div class="flex flex-1 flex-col gap-2">
      <p class="text-sm font-medium text-amber-800 dark:text-amber-100">
        {{ $t('event.dismissal.unsent_warning', { eventName }) }}
      </p>
      <div>
        <Button
          :label="$t('event.dismissal.send_button')"
          severity="warn"
          icon="pi pi-send"
          size="small"
          data-testid="event-dismissal-card-cta"
          @click="openDialog"
        />
      </div>
    </div>

    <DismissalDialog
      v-model:open="dialogOpen"
      :team-id="teamId"
      :event-id="eventId"
      @submitted="onSubmitted"
    />
  </div>
</template>
