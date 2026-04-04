<script setup lang="ts">
import type { FeatureFlagResponse } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const flags = ref<FeatureFlagResponse[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getFeatureFlags()
    flags.value = res.data
  } catch {
    showError('機能フラグの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function toggle(flag: FeatureFlagResponse) {
  try {
    await systemAdminApi.updateFeatureFlag(flag.flagKey, { isEnabled: !flag.isEnabled })
    flag.isEnabled = !flag.isEnabled
    success(`${flag.flagKey} を${flag.isEnabled ? '有効' : '無効'}にしました`)
  } catch {
    showError('更新に失敗しました')
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">機能フラグ管理</h1>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="flags" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">機能フラグがありません</div>
      </template>
      <Column field="flagKey" header="フラグキー" />
      <Column field="description" header="説明" />
      <Column header="状態" style="width: 120px">
        <template #body="{ data }">
          <Tag
            :value="data.isEnabled ? '有効' : '無効'"
            :severity="data.isEnabled ? 'success' : 'secondary'"
          />
        </template>
      </Column>
      <Column header="更新日" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.updatedAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="切替" style="width: 100px">
        <template #body="{ data }">
          <ToggleSwitch :model-value="data.isEnabled" @update:model-value="() => toggle(data)" />
        </template>
      </Column>
    </DataTable>
  </div>
</template>
