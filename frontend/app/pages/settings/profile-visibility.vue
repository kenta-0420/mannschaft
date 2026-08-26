<script setup lang="ts">
/**
 * F19.1 Phase 6: プロフィール公開設定ページ。
 *
 * ユーザーが自分のプロフィール（/public/users/{id}）を未ログインユーザーに
 * 公開するかどうかを切り替える設定ページ。
 *
 * GET  /api/v1/users/me — publicProfileEnabled を含む設定取得
 * PATCH /api/v1/users/me/public-profile — publicProfileEnabled を更新
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
 */
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const notification = useNotification()
const { getProfile, updatePublicProfile } = useUserSettingsApi()

const enabled = ref(false)
const saving = ref(false)

const { data: profileData, pending } = await useAsyncData('profile-visibility-settings', () =>
  getProfile(),
)

watchEffect(() => {
  if (profileData.value?.data) {
    enabled.value = profileData.value.data.publicProfileEnabled
  }
})

async function toggle() {
  const newValue = !enabled.value
  saving.value = true
  try {
    await updatePublicProfile({ publicProfileEnabled: newValue })
    enabled.value = newValue
    notification.success(t('public.profileVisibility.saved'))
  } catch {
    notification.error(t('public.profileVisibility.saveError'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('public.profileVisibility.title')" />

    <SectionCard :title="t('public.profileVisibility.title')">
      <div class="space-y-4">
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('public.profileVisibility.description') }}
        </p>

        <div
          data-testid="public-profile-toggle"
          class="flex items-center justify-between rounded-lg border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <div class="space-y-1">
            <p class="font-medium">{{ t('public.profileVisibility.enable') }}</p>
          </div>
          <ToggleSwitch
            :model-value="enabled"
            :disabled="pending || saving"
            :aria-label="t('public.profileVisibility.enable')"
            @update:model-value="toggle"
          />
        </div>
      </div>
    </SectionCard>
  </div>
</template>
