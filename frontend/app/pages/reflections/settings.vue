<script setup lang="ts">
/**
 * F06.5 想起通知設定（§2.7 / §7 #14-15・AC-23）。
 * remind_hour（0-23・ユーザー TZ）を設定する。未設定は既定 8 時。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()

const loading = ref(true)
const saving = ref(false)
const remindHour = ref<number>(8)

const hourOptions = computed(() =>
  Array.from({ length: 24 }, (_, h) => ({ label: t('reflection.settings.hour_format', { hour: h }), value: h })),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await reflectionApi.getSettings()
    remindHour.value = res.data.remindHour ?? 8
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await reflectionApi.updateSettings({ remindHour: remindHour.value })
    notification.success(t('reflection.settings.saved'))
  }
  catch {
    notification.error(t('reflection.settings.save_failed'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-xl px-4 py-6">
    <PageHeader
      :title="t('reflection.settings.heading')"
      back-to="/reflections"
      :back-label="t('reflection.nav.today')"
    />

    <div v-if="loading" class="space-y-3">
      <Skeleton height="80px" />
    </div>

    <div v-else class="space-y-6">
      <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.settings.remind_hour_label') }}</label>
        <Select
          v-model="remindHour"
          :options="hourOptions"
          option-label="label"
          option-value="value"
          class="w-40"
        />
        <p class="mt-2 text-xs text-surface-500">{{ t('reflection.settings.remind_hour_help') }}</p>
      </div>

      <Button :label="t('reflection.settings.save')" :loading="saving" icon="pi pi-check" class="w-full" @click="save" />
    </div>
  </div>
</template>
