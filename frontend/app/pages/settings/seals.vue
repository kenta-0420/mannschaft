<script setup lang="ts">
import type { ElectronicSeal, ScopeDefault } from '~/types/seal'

definePageMeta({ middleware: 'auth' })

const sealApi = useSealApi()
const authStore = useAuthStore()
const notification = useNotification()

const seals = ref<ElectronicSeal[]>([])
const scopeDefaults = ref<ScopeDefault[]>([])
const loading = ref(true)
const regenerating = ref(false)
const activeTab = ref('0')

const userId = computed(() => authStore.user?.id)

async function loadData() {
  if (!userId.value) return
  loading.value = true
  try {
    const [sealsRes, defaultsRes] = await Promise.all([
      sealApi.getSeals(userId.value),
      sealApi.getScopeDefaults(userId.value),
    ])
    seals.value = sealsRes
    scopeDefaults.value = defaultsRes
  } catch {
    notification.error('印鑑情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleRegenerate() {
  if (!userId.value) return
  regenerating.value = true
  try {
    seals.value = await sealApi.regenerateSeals(userId.value)
    notification.success('印鑑を再生成しました')
  } catch {
    notification.error('再生成に失敗しました（1時間に3回まで）')
  } finally {
    regenerating.value = false
  }
}

async function handleSaveDefaults(defaults: ScopeDefault[]) {
  if (!userId.value) return
  try {
    scopeDefaults.value = await sealApi.updateScopeDefaults(
      userId.value,
      defaults.map((d) => ({ scopeType: d.scopeType, scopeId: d.scopeId, variant: d.variant })),
    )
    notification.success('デフォルト設定を保存しました')
  } catch {
    notification.error('設定の保存に失敗しました')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">電子印鑑</h1>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <Tabs v-model:value="activeTab" class="fade-in">
        <TabList>
          <Tab value="0">印鑑プレビュー</Tab>
          <Tab value="1">デフォルト設定</Tab>
          <Tab value="2">押印履歴</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <div class="space-y-4">
              <SealPreview :seals="seals" />
              <div class="flex justify-center">
                <Button
                  label="印鑑を再生成"
                  icon="pi pi-refresh"
                  severity="secondary"
                  :loading="regenerating"
                  @click="handleRegenerate"
                />
              </div>
              <p class="text-center text-xs text-surface-500">
                印鑑は登録姓名から自動生成されます（1時間に3回まで）
              </p>
            </div>
          </TabPanel>
          <TabPanel value="1">
            <SealScopeDefaults :defaults="scopeDefaults" @save="handleSaveDefaults" />
          </TabPanel>
          <TabPanel value="2">
            <StampLog v-if="userId" :user-id="userId" />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>
  </div>
</template>
