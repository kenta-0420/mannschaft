<script setup lang="ts">
/**
 * F04.9 §CMP-260909-1141: チーム確認通知設定ページ。
 *
 * 従来は /admin/reservation-settings.vue（予約ライン CRUD と無関係に同居し、
 * サイドバーからリンクの無い到達不能ページ）が確認通知3コンポーネントの唯一の描画元だった。
 * 各コンポーネントは既にスコープ汎用（scopeType/scopeId を props で受け取るだけで
 * useScopeStore を直接読まない）ため、設定/配下へそのまま移設する。
 *
 * scopeId はチームシェルが解決した数値IDを使う。グローバルスコープストアは
 * この設定ページへ遷移した時点で未確定の場合があるため、依存しない。
 */
definePageMeta({ layout: 'team', middleware: ['auth', 'confirmable-notification-guard'] })

const route = useRoute()
const teamApi = useTeamApi()
const scopeId = ref('')
const scopeLoading = ref(true)
const groupsVersion = ref(0)
const templatesVersion = ref(0)

const historyRef = ref<{ refresh: () => void } | null>(null)
function onNotificationSent() {
  historyRef.value?.refresh()
}

onMounted(async () => {
  try {
    const response = await teamApi.getTeam(String(route.params.slug))
    const numericId = Number(response.data.numericId)
    if (!Number.isSafeInteger(numericId) || numericId <= 0) throw new Error('team_numeric_id_missing')
    scopeId.value = String(numericId)
  }
  catch {
    showError(createError({ statusCode: 503, statusMessage: 'Service Unavailable', fatal: true }))
  }
  finally {
    scopeLoading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('confirmable.page.settings_title')">
      <p class="text-sm text-surface-500">{{ $t('confirmable.page.settings_subtitle') }}</p>
    </PageHeader>

    <PageLoading v-if="scopeLoading" />
    <template v-else-if="scopeId">

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
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.recipient_groups') }}</h2>
      <ConfirmableRecipientGroupManager scope-type="TEAM" :scope-id="scopeId" @changed="groupsVersion++" />
    </section>

    <section class="mt-8">
      <ConfirmableTemplateManager scope-type="TEAM" :scope-id="scopeId" :groups-version="groupsVersion" @changed="templatesVersion++" />
    </section>

    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.send') }}</h2>
      <ConfirmableNotificationSender
        scope-type="TEAM"
        :scope-id="scopeId"
        :groups-version="groupsVersion + templatesVersion"
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
    </template>
  </div>
</template>
