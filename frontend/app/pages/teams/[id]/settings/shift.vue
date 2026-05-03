<script setup lang="ts">
import type { TeamShiftSettings, UpdateTeamShiftSettingsRequest } from '~/types/teamShiftSettings'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)

const notification = useNotification()

const settings = ref<TeamShiftSettings | null>(null)
const loading = ref(false)
const saving = ref(false)
const validationError = ref<string | null>(null)

// フォーム状態
const form = ref<UpdateTeamShiftSettingsRequest>({
  reminder48hEnabled: true,
  reminder24hEnabled: true,
  reminder12hEnabled: false,
})

async function loadSettings() {
  loading.value = true
  try {
    const res = await $fetch<TeamShiftSettings>(
      `/api/v1/teams/${teamId}/shift-settings`,
    )
    settings.value = res
    form.value = {
      reminder48hEnabled: res.reminder48hEnabled,
      reminder24hEnabled: res.reminder24hEnabled,
      reminder12hEnabled: res.reminder12hEnabled,
    }
  } catch {
    notification.error(t('common.error.loadFailed'))
  } finally {
    loading.value = false
  }
}

function validateAtLeastOneEnabled(): boolean {
  if (
    !form.value.reminder48hEnabled
    && !form.value.reminder24hEnabled
    && !form.value.reminder12hEnabled
  ) {
    validationError.value = t('teamShiftSettings.atLeastOneRequired')
    return false
  }
  validationError.value = null
  return true
}

async function saveSettings() {
  if (!validateAtLeastOneEnabled()) return

  saving.value = true
  try {
    const res = await $fetch<TeamShiftSettings>(
      `/api/v1/teams/${teamId}/shift-settings`,
      {
        method: 'PATCH',
        body: form.value,
      },
    )
    settings.value = res
    notification.success(t('teamShiftSettings.saved'))
  } catch {
    notification.error(t('common.error.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(() => loadSettings())
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <PageHeader :title="$t('teamShiftSettings.title')" class="mb-4" />

    <p class="mb-6 text-sm text-surface-500">
      {{ $t('teamShiftSettings.description') }}
    </p>

    <div v-if="loading" class="flex justify-center p-8">
      <ProgressSpinner />
    </div>

    <div v-else class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
      <!-- リマインド設定トグル群 -->
      <div class="flex flex-col gap-4">
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">
            {{ $t('teamShiftSettings.reminder48h') }}
          </label>
          <ToggleSwitch
            v-model="form.reminder48hEnabled"
          />
        </div>

        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">
            {{ $t('teamShiftSettings.reminder24h') }}
          </label>
          <ToggleSwitch
            v-model="form.reminder24hEnabled"
          />
        </div>

        <div class="flex items-center justify-between border-t border-surface-100 pt-4 dark:border-surface-700">
          <div>
            <label class="text-sm font-medium text-surface-400 dark:text-surface-500">
              {{ $t('teamShiftSettings.reminder12h') }}
            </label>
          </div>
          <ToggleSwitch
            v-model="form.reminder12hEnabled"
            disabled
          />
        </div>
      </div>

      <!-- バリデーションエラー -->
      <div
        v-if="validationError"
        class="mt-4 rounded-md bg-red-50 px-4 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400"
      >
        {{ validationError }}
      </div>

      <!-- 保存ボタン -->
      <div class="mt-6">
        <Button
          :label="$t('button.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="saveSettings"
        />
      </div>
    </div>
  </div>
</template>
