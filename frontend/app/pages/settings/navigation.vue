<script setup lang="ts">
import Button from 'primevue/button'
import ToggleSwitch from 'primevue/toggleswitch'

definePageMeta({ middleware: 'auth' })

const store = useNavSettingsStore()

const loading = ref(true)

onMounted(async () => {
  try {
    await store.loadFromServer()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="$t('settings.settings.navigation.title')" back-to="/settings" />
    <p class="mb-4 text-sm text-surface-500">{{ $t('settings.settings.navigation.description') }}</p>

    <PageLoading v-if="loading" size="40px" />
    <div v-else class="flex flex-col gap-2">
      <SectionCard
        v-for="item in store.features"
        :key="item.key"
      >
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <i :class="item.icon" class="text-lg text-primary" />
            <span class="text-sm font-medium">{{ $t(item.labelKey, item.labelKey) }}</span>
          </div>
          <div class="flex items-center gap-2">
            <span
              v-if="item.fixed"
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700"
            >
              <i class="pi pi-lock text-xs" />
              {{ $t('settings.settings.navigation.required') }}
            </span>
            <ToggleSwitch
              :model-value="item.visible"
              :disabled="item.fixed"
              @update:model-value="(v: boolean) => store.setVisibility(item.key, v)"
            />
          </div>
        </div>
      </SectionCard>
    </div>

    <div class="mt-6 flex justify-end">
      <Button
        :label="$t('settings.settings.navigation.resetToDefault')"
        severity="secondary"
        outlined
        size="small"
        @click="store.resetToDefault()"
      />
    </div>
  </div>
</template>
