<script setup lang="ts">
import type { GamificationConfig } from '~/types/gamification'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const gamificationApi = useGamificationApi()
const notification = useNotification()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgId)

const config = ref<GamificationConfig | null>(null)
const loading = ref(true)
const saving = ref(false)

async function loadData() {
  loading.value = true
  try {
    await loadPermissions()
    config.value = await gamificationApi.getConfig('organization', orgId.value)
  } catch {
    notification.error('ゲーミフィケーション設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  if (!config.value) return
  saving.value = true
  try {
    await gamificationApi.updateConfig('organization', orgId.value, config.value)
    notification.success('設定を保存しました')
  } catch {
    notification.error('設定の保存に失敗しました')
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <h1 class="mb-6 text-2xl font-bold">ゲーミフィケーション設定</h1>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <template v-else-if="config">
      <div class="space-y-6">
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800">
          <h2 class="mb-4 text-lg font-semibold">機能の有効/無効</h2>
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <div><p class="font-medium">ゲーミフィケーション全体</p><p class="text-xs text-surface-500">ポイント・バッジ・ランキング機能を有効にする</p></div>
              <ToggleSwitch v-model="config.enabled" :disabled="!isAdmin" />
            </div>
            <div class="flex items-center justify-between">
              <div><p class="font-medium">ポイント機能</p><p class="text-xs text-surface-500">アクションに応じたポイント付与</p></div>
              <ToggleSwitch v-model="config.pointsEnabled" :disabled="!isAdmin || !config.enabled" />
            </div>
            <div class="flex items-center justify-between">
              <div><p class="font-medium">バッジ機能</p><p class="text-xs text-surface-500">条件達成時のバッジ自動付与</p></div>
              <ToggleSwitch v-model="config.badgesEnabled" :disabled="!isAdmin || !config.enabled" />
            </div>
            <div class="flex items-center justify-between">
              <div><p class="font-medium">ランキング機能</p><p class="text-xs text-surface-500">ポイントランキングの表示</p></div>
              <ToggleSwitch v-model="config.rankingEnabled" :disabled="!isAdmin || !config.enabled" />
            </div>
          </div>
        </div>

        <div v-if="isAdmin" class="flex justify-end">
          <Button label="設定を保存" icon="pi pi-check" :loading="saving" @click="saveConfig" />
        </div>
      </div>
    </template>
  </div>
</template>
