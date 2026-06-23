<script setup lang="ts">
import Button from 'primevue/button'
import ToggleSwitch from 'primevue/toggleswitch'
import draggable from 'vuedraggable'
import type { NavFeatureItem } from '~/types/nav'

definePageMeta({ middleware: 'auth' })

const store = useNavSettingsStore()

const loading = ref(true)

// D&D 中のローカル配列。確定（drag 終了）で store.reorderNav に反映する。
const localItems = ref<NavFeatureItem[]>([])

// store.features が更新されたらローカル配列を同期する（初期化・ロールバック追従）。
watch(
  () => store.features,
  (features) => {
    localItems.value = features.map(f => ({ ...f }))
  },
  { immediate: true, deep: true },
)

onMounted(async () => {
  try {
    await store.loadFromServer()
  } finally {
    loading.value = false
  }
})

/** ドラッグ終了で並び替えを確定し、サーバーへ永続化する。 */
async function onDragEnd() {
  const order = localItems.value.map(f => f.key)
  await store.reorderNav(order)
}

/** 表示 ON/OFF トグル。ローカル配列も即時反映する。 */
async function onToggleVisibility(key: string, visible: boolean) {
  await store.setVisibility(key, visible)
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="$t('settings.navigation.title')" back-to="/settings" />
    <p class="mb-2 text-sm text-surface-500">{{ $t('settings.navigation.description') }}</p>
    <p class="mb-4 text-xs text-surface-400">
      <i class="pi pi-bars mr-1" />{{ $t('settings.navigation.reorderHint') }}
    </p>

    <PageLoading v-if="loading" size="40px" />
    <draggable
      v-else
      v-model="localItems"
      item-key="key"
      handle=".drag-handle"
      :animation="150"
      ghost-class="opacity-30"
      class="flex flex-col gap-2"
      data-testid="nav-order-list"
      @end="onDragEnd"
    >
      <template #item="{ element }">
        <SectionCard>
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <i
                class="drag-handle pi pi-bars cursor-move text-surface-400"
                :aria-label="$t('settings.navigation.dragHandleLabel')"
              />
              <i :class="element.icon" class="text-lg text-primary" />
              <span class="text-sm font-medium">{{ $t(element.labelKey, element.labelKey) }}</span>
            </div>
            <div class="flex items-center gap-2">
              <span
                v-if="element.fixed"
                class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700"
              >
                <i class="pi pi-lock text-xs" />
                {{ $t('settings.navigation.required') }}
              </span>
              <ToggleSwitch
                :model-value="element.visible"
                :disabled="element.fixed"
                @update:model-value="(v: boolean) => onToggleVisibility(element.key, v)"
              />
            </div>
          </div>
        </SectionCard>
      </template>
    </draggable>

    <div class="mt-6 flex justify-end">
      <Button
        :label="$t('settings.navigation.resetToDefault')"
        severity="secondary"
        outlined
        size="small"
        @click="store.resetToDefault()"
      />
    </div>
  </div>
</template>
