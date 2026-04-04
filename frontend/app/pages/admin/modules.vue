<script setup lang="ts">
import type { ModuleResponse } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const modules = ref<ModuleResponse[]>([])
const loading = ref(true)
const selectedModule = ref<ModuleResponse | null>(null)
const showDetailDialog = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getModules()
    modules.value = res.data
  } catch {
    showError('モジュール一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function viewDetail(mod: ModuleResponse) {
  try {
    const res = await systemAdminApi.getModule(mod.id)
    selectedModule.value = res.data
    showDetailDialog.value = true
  } catch {
    showError('モジュール詳細の取得に失敗しました')
  }
}

async function updateLevelAvailability(moduleId: number, level: string, isAvailable: boolean) {
  try {
    await systemAdminApi.updateModuleLevelAvailability(moduleId, { level, isAvailable })
    success('レベル別利用可否を更新しました')
    if (selectedModule.value) {
      const la = selectedModule.value.levelAvailability.find((l) => l.level === level)
      if (la) la.isAvailable = isAvailable
    }
  } catch {
    showError('更新に失敗しました')
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <h1 class="mb-6 text-2xl font-bold">モジュール管理</h1>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="modules" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">モジュールがありません</div>
      </template>
      <Column field="name" header="モジュール名" />
      <Column field="slug" header="スラグ" />
      <Column field="moduleType" header="タイプ" style="width: 100px" />
      <Column header="番号" style="width: 80px">
        <template #body="{ data }">
          {{ data.moduleNumber }}
        </template>
      </Column>
      <Column header="有料プラン" style="width: 100px">
        <template #body="{ data }">
          <Tag
            :value="data.requiresPaidPlan ? '必要' : '不要'"
            :severity="data.requiresPaidPlan ? 'warn' : 'success'"
          />
        </template>
      </Column>
      <Column header="状態" style="width: 80px">
        <template #body="{ data }">
          <Tag
            :value="data.isActive ? '有効' : '無効'"
            :severity="data.isActive ? 'success' : 'secondary'"
          />
        </template>
      </Column>
      <Column header="操作" style="width: 100px">
        <template #body="{ data }">
          <Button label="詳細" size="small" severity="info" text @click="viewDetail(data)" />
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDetailDialog"
      header="モジュール詳細"
      :style="{ width: '600px' }"
      modal
    >
      <div v-if="selectedModule" class="flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-3">
          <div>
            <p class="text-xs text-surface-500">モジュール名</p>
            <p class="font-medium">{{ selectedModule.name }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-500">スラグ</p>
            <p class="font-medium">{{ selectedModule.slug }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-500">説明</p>
            <p class="text-sm">{{ selectedModule.description || '-' }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-500">トライアル日数</p>
            <p class="font-medium">{{ selectedModule.trialDays }}日</p>
          </div>
        </div>

        <div v-if="selectedModule.levelAvailability?.length">
          <h3 class="mb-2 text-sm font-semibold">レベル別利用可否</h3>
          <div class="space-y-2">
            <div
              v-for="la in selectedModule.levelAvailability"
              :key="la.level"
              class="flex items-center justify-between rounded-lg border border-surface-200 p-3"
            >
              <div>
                <span class="font-medium">{{ la.level }}</span>
                <span v-if="la.note" class="ml-2 text-xs text-surface-500">{{ la.note }}</span>
              </div>
              <ToggleSwitch
                :model-value="la.isAvailable"
                @update:model-value="
                  (v: boolean) => updateLevelAvailability(selectedModule!.id, la.level, v)
                "
              />
            </div>
          </div>
        </div>

        <div v-if="selectedModule.recommendations?.length">
          <h3 class="mb-2 text-sm font-semibold">推奨モジュール</h3>
          <div class="flex flex-wrap gap-2">
            <Tag
              v-for="rec in selectedModule.recommendations"
              :key="rec.id"
              :value="rec.name"
              severity="info"
            />
          </div>
        </div>
      </div>
    </Dialog>
  </div>
</template>
