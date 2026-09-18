<script setup lang="ts">
/**
 * F04.9 §CMP-260909-1141: 組織確認通知設定ページ。
 *
 * teams/[slug]/settings/confirmable-notifications.vue の組織版。
 * 背景・移設理由はそちらの Javadoc コメントを参照。
 */
definePageMeta({ layout: 'organization', middleware: ['auth', 'org-role-guard'] })

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
        scope-type="ORGANIZATION"
        :scope-id="scopeId"
      />
    </section>

    <!-- 確認通知送信セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.send') }}</h2>
      <ConfirmableNotificationSender
        scope-type="ORGANIZATION"
        :scope-id="scopeId"
        @sent="onNotificationSent"
      />
    </section>

    <!-- 発信履歴セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.history') }}</h2>
      <ConfirmableNotificationHistory
        ref="historyRef"
        scope-type="ORGANIZATION"
        :scope-id="scopeId"
      />
    </section>
  </div>
</template>
