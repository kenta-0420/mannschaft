<script setup lang="ts">
/**
 * F19.1 Phase 6-A: プロフィール公開設定ページ。
 *
 * ユーザーが自分のプロフィール（/public/users/{id}）を未ログインユーザーに
 * 公開するかどうかを切り替える設定ページ。
 *
 * TODO: バックエンドの public_profile_enabled 更新エンドポイントが未実装のため
 * 現在は placeholder（機能説明のみ）として表示する。
 * 実装予定エンドポイント:
 *   GET  /api/v1/users/me/settings — publicProfileEnabled を含む設定取得
 *   PATCH /api/v1/users/me/settings — publicProfileEnabled を更新
 * 実装完了後にこの TODO とプレースホルダー表示を削除すること。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
 */
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <BackButton to="/settings" />
    <PageHeader :title="t('public.profileVisibility.title')" />

    <SectionCard :title="t('public.profileVisibility.title')">
      <div class="space-y-4">
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('public.profileVisibility.description') }}
        </p>

        <!-- TODO: バックエンド API 実装後に実際のトグルスイッチに置き換える -->
        <div
          data-testid="public-profile-toggle"
          class="flex items-center justify-between rounded-lg border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <div class="space-y-1">
            <p class="font-medium">{{ t('public.profileVisibility.enable') }}</p>
            <p class="text-xs text-surface-500">
              {{ t('public.profileVisibility.comingSoon') }}
            </p>
          </div>
          <ToggleSwitch
            :model-value="false"
            :disabled="true"
            aria-label="t('public.profileVisibility.enable')"
          />
        </div>

        <Message severity="info" :closable="false">
          {{ t('public.profileVisibility.apiNotReady') }}
        </Message>
      </div>
    </SectionCard>
  </div>
</template>
