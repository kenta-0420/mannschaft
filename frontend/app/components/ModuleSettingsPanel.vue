<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const adminApi = useAdminDashboardApi()
const notification = useNotification()

interface Module {
  moduleId: string
  name: string
  enabled: boolean
}

const modules = ref<Module[]>([])
const loading = ref(false)
const togglingIds = ref<string[]>([])

async function fetchModules() {
  loading.value = true
  try {
    const data = await adminApi.listModules(props.scopeType, props.scopeId)
    modules.value = data
  } catch {
    notification.error('機能一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function toggle(moduleId: string, enabled: boolean) {
  togglingIds.value.push(moduleId)
  try {
    await adminApi.toggleModule(props.scopeType, props.scopeId, moduleId, enabled)
    const mod = modules.value.find((m) => m.moduleId === moduleId)
    if (mod) mod.enabled = enabled
    notification.success(`${mod?.name ?? moduleId}を${enabled ? '有効' : '無効'}にしました`)
  } catch {
    notification.error('機能の切替に失敗しました')
  } finally {
    togglingIds.value = togglingIds.value.filter((id) => id !== moduleId)
  }
}

const enabledCount = computed(() => modules.value.filter((m) => m.enabled).length)

onMounted(fetchModules)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <p class="text-sm text-gray-500">
        有効な機能:
        <span class="font-semibold text-gray-800">{{ enabledCount }}</span>
        / {{ modules.length }}
      </p>
      <Button
        icon="pi pi-refresh"
        text
        rounded
        size="small"
        :loading="loading"
        @click="fetchModules"
      />
    </div>

    <div v-if="loading" class="flex justify-center py-10">
      <ProgressSpinner style="width: 36px; height: 36px" />
    </div>

    <div
      v-else-if="modules.length === 0"
      class="rounded-lg border border-dashed border-gray-300 py-10 text-center text-sm text-gray-500"
    >
      <i class="pi pi-box mb-2 block text-2xl" />
      <p class="font-medium">利用可能な機能がありません</p>
      <p class="mt-1 text-xs">システム管理 &gt; モジュール管理 でモジュールを登録してください</p>
    </div>

    <div v-else class="grid grid-cols-1 gap-2 sm:grid-cols-2">
      <div
        v-for="mod in modules"
        :key="mod.moduleId"
        class="flex items-center justify-between rounded-xl border p-4 transition-colors"
        :class="
          mod.enabled
            ? 'border-primary-200 bg-primary-50/40 dark:border-primary-800 dark:bg-primary-950/30'
            : 'border-surface-200 bg-surface-0 dark:border-surface-600 dark:bg-surface-800'
        "
      >
        <div class="flex items-center gap-3">
          <div
            class="flex size-9 shrink-0 items-center justify-center rounded-lg"
            :class="
              mod.enabled
                ? 'bg-primary-100 text-primary dark:bg-primary-900'
                : 'bg-surface-100 text-surface-400 dark:bg-surface-700'
            "
          >
            <i class="pi pi-puzzle text-base" />
          </div>
          <div>
            <p class="font-medium leading-tight">{{ mod.name }}</p>
            <p class="text-xs text-gray-400">{{ mod.moduleId }}</p>
          </div>
        </div>
        <ToggleSwitch
          :model-value="mod.enabled"
          :disabled="togglingIds.includes(mod.moduleId)"
          class="ml-3 shrink-0"
          @update:model-value="(v: boolean) => toggle(mod.moduleId, v)"
        />
      </div>
    </div>
  </div>
</template>
