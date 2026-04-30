<script setup lang="ts">
import type { DigestConfigResponse, DigestConfigRequest } from '~/types/timeline-digest'

defineProps<{
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: number
}>()

const { getConfig, updateConfig } = useTimelineDigestApi()
const notification = useNotification()
const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)

const config = ref<DigestConfigRequest>({
  scopeId: 0,
  digestStyle: 'SUMMARY',
  autoPublish: false,
  includeReactions: true,
  includePolls: true,
})

const autoGenerateEnabled = ref(false)
const cronExpression = ref('')
const includeAnonymousMembers = ref(false)

const styleOptions = computed(() => [
  { label: t('timeline_digest.style_summary'), value: 'SUMMARY' },
  { label: t('timeline_digest.style_narrative'), value: 'NARRATIVE' },
  { label: t('timeline_digest.style_highlights'), value: 'HIGHLIGHTS' },
  { label: t('timeline_digest.style_template'), value: 'TEMPLATE' },
])

async function loadConfig() {
  loading.value = true
  try {
    const res = await getConfig()
    const data: DigestConfigResponse = res.data
    config.value.digestStyle = data.digestStyle
    config.value.autoPublish = data.autoPublish
    config.value.includeReactions = data.includeReactions
    config.value.includePolls = data.includePolls
    autoGenerateEnabled.value = data.isEnabled
    cronExpression.value = data.scheduleTime ?? ''
  } catch {
    notification.error(t('timeline_digest.error_load_config'))
  } finally {
    loading.value = false
  }
}

async function saveConfig() {
  saving.value = true
  try {
    await updateConfig({
      ...config.value,
      scheduleTime: cronExpression.value || undefined,
    })
    notification.success(t('timeline_digest.success_save_config'))
  } catch {
    notification.error(t('timeline_digest.error_save_config'))
  } finally {
    saving.value = false
  }
}

onMounted(loadConfig)
</script>

<template>
  <div class="space-y-4">
    <PageLoading v-if="loading" size="32px" />
    <template v-else>
      <div class="flex items-center justify-between">
        <label class="font-medium">{{ $t('timeline_digest.label_auto_generate') }}</label>
        <ToggleSwitch v-model="autoGenerateEnabled" />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_digest_style') }}</label>
        <Select
          v-model="config.digestStyle"
          :options="styleOptions"
          option-label="label"
          option-value="value"
          :placeholder="$t('timeline_digest.placeholder_select_style')"
          class="w-full"
        />
      </div>

      <div class="flex items-center gap-3">
        <Checkbox v-model="includeAnonymousMembers" :binary="true" input-id="includeAnon" />
        <label for="includeAnon" class="text-sm font-medium">{{ $t('timeline_digest.label_include_anonymous') }}</label>
      </div>

      <div v-if="autoGenerateEnabled" class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_cron_expression') }}</label>
        <InputText
          v-model="cronExpression"
          :placeholder="$t('timeline_digest.placeholder_cron')"
          class="w-full"
        />
        <small class="text-surface-500">{{ $t('timeline_digest.hint_cron') }}</small>
      </div>

      <div class="flex justify-end pt-2">
        <Button
          :label="$t('button.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="saveConfig"
        />
      </div>
    </template>
  </div>
</template>
