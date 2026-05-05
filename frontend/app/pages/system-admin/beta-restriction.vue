<script setup lang="ts">
import type { BetaRestrictionConfigResponse } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const systemAdminApi = useSystemAdminApi()
const notification = useNotification()

const config = ref<BetaRestrictionConfigResponse | null>(null)
const loading = ref(true)
const saving = ref(false)

// フォーム値
const formEnabled = ref(false)
const formMaxTeamId = ref<number | null>(null)
const formMaxOrgId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getBetaRestrictionConfig()
    config.value = res.data
    formEnabled.value = res.data.isEnabled
    formMaxTeamId.value = res.data.maxTeamId
    formMaxOrgId.value = res.data.maxOrgId
  } catch {
    notification.error('設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const res = await systemAdminApi.updateBetaRestrictionConfig({
      isEnabled: formEnabled.value,
      maxTeamId: formMaxTeamId.value,
      maxOrgId: formMaxOrgId.value,
    })
    config.value = res.data
    notification.success(t('beta_restriction.save_success'))
  } catch {
    notification.error('設定の保存に失敗しました')
  } finally {
    saving.value = false
  }
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  load()
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <h1 class="mb-2 text-2xl font-bold">{{ $t('beta_restriction.title') }}</h1>
    <p class="mb-6 text-surface-500">{{ $t('beta_restriction.description') }}</p>

    <PageLoading v-if="loading" />

    <template v-else>
      <div class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800">
        <div class="mb-4 flex items-center gap-3">
          <ToggleSwitch v-model="formEnabled" input-id="betaEnabled" />
          <label for="betaEnabled" class="cursor-pointer font-medium">
            {{ $t('beta_restriction.enabled_label') }}
          </label>
        </div>

        <div class="mb-4 flex flex-col gap-2">
          <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
            {{ $t('beta_restriction.max_team_id_label') }}
          </label>
          <InputNumber
            v-model="formMaxTeamId"
            :min="1"
            :show-buttons="false"
            :placeholder="$t('beta_restriction.null_hint')"
            class="w-full"
          />
          <small class="text-surface-400">{{ $t('beta_restriction.null_hint') }}</small>
        </div>

        <div class="mb-6 flex flex-col gap-2">
          <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
            {{ $t('beta_restriction.max_org_id_label') }}
          </label>
          <InputNumber
            v-model="formMaxOrgId"
            :min="1"
            :show-buttons="false"
            :placeholder="$t('beta_restriction.null_hint')"
            class="w-full"
          />
          <small class="text-surface-400">{{ $t('beta_restriction.null_hint') }}</small>
        </div>

        <Button
          :label="$t('beta_restriction.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="save"
        />
      </div>

      <!-- 現在の設定表示 -->
      <div
        v-if="config"
        class="rounded-xl border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-900"
      >
        <h2 class="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-400">
          {{ $t('beta_restriction.current_settings') }}
        </h2>
        <div class="space-y-2 text-sm">
          <div class="flex items-center justify-between">
            <span class="text-surface-500">制限</span>
            <Tag
              :value="config.isEnabled ? $t('beta_restriction.status_on') : $t('beta_restriction.status_off')"
              :severity="config.isEnabled ? 'warn' : 'success'"
            />
          </div>
          <div class="flex items-center justify-between">
            <span class="text-surface-500">{{ $t('beta_restriction.max_team_id_label') }}</span>
            <span class="font-medium">
              {{ config.maxTeamId ?? $t('beta_restriction.no_limit') }}
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-surface-500">{{ $t('beta_restriction.max_org_id_label') }}</span>
            <span class="font-medium">
              {{ config.maxOrgId ?? $t('beta_restriction.no_limit') }}
            </span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-surface-500">{{ $t('beta_restriction.last_updated') }}</span>
            <span class="font-medium">{{ formatDate(config.updatedAt) }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
