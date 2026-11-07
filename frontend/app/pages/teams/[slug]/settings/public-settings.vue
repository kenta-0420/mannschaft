<script setup lang="ts">
import type { PublicSettingsResponse, UpdatePublicSettingsRequest } from '~/types/public'

/** 非公開のチームも、公開閲覧APIを使わず管理認可済みの設定を編集する。 */
definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { fetchTeamPublicSettings, updateTeamPublicSettings } = usePublicApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)
const { handleApiError } = useErrorHandler()
const { success } = useNotification()
const settings = shallowRef<PublicSettingsResponse | null>(null)
const timelinePostsPublic = ref(false)
const publicEventsEnabled = ref(false)
const loading = ref(true)
const saving = ref(false)
const loadError = shallowRef<unknown>(null)

async function onSlugRenamed(newSlug: string) {
  await navigateTo(`/teams/${newSlug}/settings/public-settings`, { replace: true })
}

let loadSequence = 0
async function loadSettings() {
  const sequence = ++loadSequence
  const requestedSlug = teamSlug.value
  const isCurrent = () => sequence === loadSequence && requestedSlug === teamSlug.value
  loading.value = true
  loadError.value = null
  settings.value = null
  try {
    const result = await loadPermissions()
    if (!isCurrent()) return
    if (!result.ok) throw result.error
    if (!isAdminOrDeputy.value) throw createError({ statusCode: 403 })
    const response = await fetchTeamPublicSettings(requestedSlug)
    if (!isCurrent()) return
    settings.value = response
    timelinePostsPublic.value = response.timelinePostsPublic
    publicEventsEnabled.value = response.publicEventsEnabled
  } catch (error) {
    if (isCurrent()) {
      loadError.value = error
      handleApiError(error)
    }
  } finally {
    if (isCurrent()) loading.value = false
  }
}

async function save() {
  if (!settings.value || loading.value || loadError.value || saving.value) return
  saving.value = true
  try {
    // チームでは非表示のイベント公開設定も、取得値を保持して両必須値を送る。
    const req: UpdatePublicSettingsRequest = {
      timelinePostsPublic: timelinePostsPublic.value,
      publicEventsEnabled: publicEventsEnabled.value,
    }
    await updateTeamPublicSettings(settings.value.numericId, req)
    success(t('public.publicSettings.saved'))
  } catch (error) {
    handleApiError(error)
  } finally {
    saving.value = false
  }
}

onMounted(loadSettings)
watch(teamSlug, loadSettings)
</script>

<template>
  <div class="mx-auto max-w-2xl space-y-6 p-4">
    <h1 class="text-2xl font-bold">
      {{ t('public.publicSettings.title') }}
    </h1>

    <div v-if="loading" class="flex items-center justify-center py-10">
      <ProgressSpinner />
    </div>

    <DashboardErrorState v-if="!loading && loadError" :error="loadError" @retry="loadSettings" />

    <SlugRenameField
      v-if="!loading && !loadError && settings && isAdminOrDeputy"
      entity-type="team"
      :current-slug="teamSlug"
      @renamed="onSlugRenamed"
    />

    <form
      v-if="!loading && !loadError && settings && isAdminOrDeputy"
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
          class="min-h-11"
          :loading="saving"
          :label="t('common.save')"
          data-testid="save-public-settings"
        />
      </div>
    </form>
  </div>
</template>
