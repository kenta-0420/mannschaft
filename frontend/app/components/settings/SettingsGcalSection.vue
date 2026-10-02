<script setup lang="ts">
/**
 * アカウント設定ページの Google Calendar 連携カード。
 *
 * チーム/組織別の同期ON/OFFトグルは詳細設定ページ（/settings/calendar-sync）に一本化したため、
 * ここでは接続状態の簡易表示と詳細設定への導線のみを持つ（AC-14）。
 */
defineProps<{
  gcalStatus: {
    connected: boolean
    googleAccountEmail: string | null
  } | null
}>()

function goToDetail() {
  navigateTo('/settings/calendar-sync')
}
</script>

<template>
  <SectionCard :title="$t('settings.gcal.section_title')">
    <div v-if="gcalStatus?.connected" class="flex items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
        >
          <i class="pi pi-check text-green-600" />
        </div>
        <div>
          <p class="font-medium text-green-700 dark:text-green-400">
            {{ $t('settings.gcal.connected_label') }}
          </p>
          <p class="text-sm text-surface-500">{{ gcalStatus.googleAccountEmail }}</p>
        </div>
      </div>
      <Button
        :label="$t('settings.gcal.detail_link_button')"
        icon="pi pi-arrow-right"
        icon-pos="right"
        text
        size="small"
        data-testid="gcal-detail-link"
        @click="goToDetail"
      />
    </div>
    <div v-else class="flex items-center justify-between gap-3">
      <p class="text-sm text-surface-500">
        {{ $t('settings.gcal.not_connected_description') }}
      </p>
      <Button
        :label="$t('settings.gcal.detail_link_button')"
        icon="pi pi-arrow-right"
        icon-pos="right"
        text
        size="small"
        data-testid="gcal-detail-link"
        @click="goToDetail"
      />
    </div>
  </SectionCard>
</template>
