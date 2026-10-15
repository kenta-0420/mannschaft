<script setup lang="ts">
// F08.10 相手チーム記録イベントへの編集試行時の案内（04_frontend_and_ux.md §G.10）。
// 「○○チームが入力。訂正は依頼してください」を表示し、直接編集不可であることを示す。
defineProps<{
  /** 相手チーム名（無ければ既定ラベル） */
  teamName: string
}>()

const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm">
    <div class="mb-1 flex items-center gap-2 font-medium text-amber-800">
      <i class="pi pi-info-circle" />
      {{ t('match.live.opponent_notice.title', { team: teamName }) }}
    </div>
    <p class="text-amber-700">
      {{ t('match.live.opponent_notice.detail', { team: teamName }) }}
    </p>
    <div class="mt-3 flex justify-end">
      <Button size="small" text :label="t('match.live.flow.back')" @click="emit('close')" />
    </div>
  </div>
</template>
