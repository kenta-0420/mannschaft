<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const adminApi = useAdminDashboardApi()
const notification = useNotification()

const dashboard = ref<Record<string, unknown> | null>(null)
const modules = ref<{ moduleId: string; name: string; enabled: boolean }[]>([])
const loading = ref(true)
const activeTab = ref('0')

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.currentScope?.type ?? 'team')
const scopeId = computed(() => scopeStore.currentScope?.id ?? 0)

async function loadData() {
  if (!scopeId.value) return
  loading.value = true
  try {
    const [dashData, modsData] = await Promise.all([
      adminApi.getDashboard(scopeType.value as 'team' | 'organization', scopeId.value),
      adminApi.listModules(scopeType.value as 'team' | 'organization', scopeId.value),
    ])
    dashboard.value = dashData
    modules.value = modsData
  } catch {
    notification.error('管理ダッシュボードの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function toggleModule(moduleId: string, enabled: boolean) {
  try {
    await adminApi.toggleModule(scopeType.value as 'team' | 'organization', scopeId.value, moduleId, enabled)
    const mod = modules.value.find(m => m.moduleId === moduleId)
    if (mod) mod.enabled = enabled
    notification.success(`モジュールを${enabled ? '有効' : '無効'}にしました`)
  } catch {
    notification.error('モジュールの切替に失敗しました')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">管理者ダッシュボード</h1>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <template v-else>
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">概要</Tab>
          <Tab value="1">モジュール管理</Tab>
          <Tab value="2">通報管理</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <div v-if="dashboard" class="grid gap-4 md:grid-cols-4">
              <Card v-for="(value, key) in dashboard" :key="String(key)">
                <template #content>
                  <p class="text-sm text-surface-500">{{ key }}</p>
                  <p class="text-2xl font-bold text-primary">{{ value }}</p>
                </template>
              </Card>
            </div>
            <div v-else class="py-8 text-center text-surface-500">データがありません</div>
          </TabPanel>
          <TabPanel value="1">
            <div class="space-y-3">
              <div
                v-for="mod in modules"
                :key="mod.moduleId"
                class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
              >
                <div>
                  <p class="font-medium">{{ mod.name }}</p>
                  <p class="text-xs text-surface-500">{{ mod.moduleId }}</p>
                </div>
                <ToggleSwitch :model-value="mod.enabled" @update:model-value="(v: boolean) => toggleModule(mod.moduleId, v)" />
              </div>
            </div>
          </TabPanel>
          <TabPanel value="2">
            <NuxtLink to="/admin/moderation">
              <Button label="通報・モデレーション管理へ" icon="pi pi-external-link" />
            </NuxtLink>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
