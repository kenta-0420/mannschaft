<script setup lang="ts">
/**
 * Google Calendar連携（チーム/組織カレンダー同期）の使い方を案内するモーダルダイアログ。
 *
 * SecurityHelpDialog / ProjectGuideModal と同じ
 * 「色付き丸アイコン＋カード」方式で見やすくした。
 * コンテンツ本体は CalendarSyncHelpContent コンポーネントに切り出す。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('calendar_sync_help.title')"
    class="w-full max-w-2xl"
    data-testid="calendar-sync-help-modal"
  >
    <div class="overflow-y-auto py-2">
      <CalendarSyncHelpContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
