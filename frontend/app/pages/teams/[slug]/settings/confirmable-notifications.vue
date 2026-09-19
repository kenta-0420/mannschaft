<script setup lang="ts">
/**
 * F04.9 §CMP-260909-1141: チーム確認通知設定ページ。
 *
 * 従来は /admin/reservation-settings.vue（予約ライン CRUD と無関係に同居し、
 * サイドバーからリンクの無い到達不能ページ）が確認通知3コンポーネントの唯一の描画元だった。
 * 各コンポーネントは既にスコープ汎用（scopeType/scopeId を props で受け取るだけで
 * useScopeStore を直接読まない）ため、設定/配下へそのまま移設する。
 *
 * scopeId の解決方式は移設元と同じく useScopeStore().current を使う
 * （/teams/[slug]/... 配下では plugins/scope.client.ts が URL の slug から
 * 数値 team id を解決し、ページマウント時点で current に反映済み）。
 */
definePageMeta({ layout: 'team', middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? '')

const historyRef = ref<{ refresh: () => void } | null>(null)
function onNotificationSent() {
  historyRef.value?.refresh()
}
</script>

<template>
  <div class="mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('confirmable.page.settings_title')">
      <p class="text-sm text-surface-500">{{ $t('confirmable.page.settings_subtitle') }}</p>
    </PageHeader>

    <!-- 確認通知設定セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.settings') }}</h2>
      <ConfirmableNotificationSettings
        scope-type="TEAM"
        :scope-id="scopeId"
      />
    </section>

    <!-- 確認通知送信セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.send') }}</h2>
      <ConfirmableNotificationSender
        scope-type="TEAM"
        :scope-id="scopeId"
        @sent="onNotificationSent"
      />
    </section>

    <!-- 発信履歴セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.history') }}</h2>
      <ConfirmableNotificationHistory
        ref="historyRef"
        scope-type="TEAM"
        :scope-id="scopeId"
      />
    </section>
  </div>
</template>
