<script setup lang="ts">
import type { UpdatePublicSettingsRequest } from '~/types/public'

/**
 * F19.1 Phase 7: チーム公開設定ページ。
 *
 * - timelinePostsPublic: タイムライン投稿の公開設定
 * - 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §8.2 / Phase 7
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = String(route.params.id)
const toast = useToast()

const { fetchPublicTeam, updateTeamPublicSettings } = usePublicApi()

const timelinePostsPublic = ref(false)
const loading = ref(false)
const saving = ref(false)

async function loadSettings() {
  loading.value = true
  try {
    const team = await fetchPublicTeam(teamId)
    timelinePostsPublic.value = team.timelinePostsPublic
  }
  finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const req: UpdatePublicSettingsRequest = {
      timelinePostsPublic: timelinePostsPublic.value,
    }
    await updateTeamPublicSettings(teamId, req)
    toast.add({ severity: 'success', summary: t('public.publicSettings.saved'), life: 3000 })
  }
  catch {
    toast.add({ severity: 'error', summary: t('public.publicSettings.saveError'), life: 5000 })
  }
  finally {
    saving.value = false
  }
}

await loadSettings()
</script>

<template>
  <div class="mx-auto max-w-2xl space-y-6 p-4">
    <h1 class="text-2xl font-bold">
      {{ t('public.publicSettings.title') }}
    </h1>

    <div
      v-if="loading"
      class="flex items-center justify-center py-10"
    >
      <ProgressSpinner />
    </div>

    <form
      v-else
      data-testid="team-public-settings-form"
      class="space-y-6 rounded-lg border border-surface-200 p-6 dark:border-surface-700"
      @submit.prevent="save"
    >
      <div class="flex items-start justify-between gap-4">
        <div class="flex-1">
          <label class="block text-sm font-medium" for="timeline-posts-public">
            {{ t('public.publicSettings.timelinePostsPublic') }}
          </label>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('public.publicSettings.timelinePostsPublicDescription') }}
          </p>
        </div>
        <ToggleSwitch
          id="timeline-posts-public"
          v-model="timelinePostsPublic"
          data-testid="toggle-timeline-posts-public"
        />
      </div>

      <div class="flex justify-end">
        <Button
          type="submit"
          :loading="saving"
          :label="t('common.save')"
          data-testid="save-public-settings"
        />
      </div>
    </form>
  </div>
</template>
