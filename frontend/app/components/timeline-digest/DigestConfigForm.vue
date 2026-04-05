<script setup lang="ts">
import type { DigestConfigResponse, DigestConfigRequest } from '~/types/timeline-digest'

defineProps<{
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: number
}>()

const { getConfig, updateConfig } = useTimelineDigestApi()
const notification = useNotification()

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

const styleOptions = [
  { label: '要約 (SUMMARY)', value: 'SUMMARY' },
  { label: 'ナラティブ (NARRATIVE)', value: 'NARRATIVE' },
  { label: 'ハイライト (HIGHLIGHTS)', value: 'HIGHLIGHTS' },
  { label: 'テンプレート (TEMPLATE)', value: 'TEMPLATE' },
]

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
    notification.error('設定の取得に失敗しました')
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
    notification.success('設定を保存しました')
  } catch {
    notification.error('設定の保存に失敗しました')
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
        <label class="font-medium">自動生成を有効にする</label>
        <ToggleSwitch v-model="autoGenerateEnabled" />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">ダイジェストスタイル</label>
        <Select
          v-model="config.digestStyle"
          :options="styleOptions"
          option-label="label"
          option-value="value"
          placeholder="スタイルを選択"
          class="w-full"
        />
      </div>

      <div class="flex items-center gap-3">
        <Checkbox v-model="includeAnonymousMembers" :binary="true" input-id="includeAnon" />
        <label for="includeAnon" class="text-sm font-medium">匿名メンバーを含む</label>
      </div>

      <div v-if="autoGenerateEnabled" class="flex flex-col gap-1">
        <label class="text-sm font-medium">Cron式（スケジュール）</label>
        <InputText
          v-model="cronExpression"
          placeholder="例: 0 9 * * 1"
          class="w-full"
        />
        <small class="text-surface-500">例: 0 9 * * 1（毎週月曜9時）</small>
      </div>

      <div class="flex justify-end pt-2">
        <Button
          label="保存"
          icon="pi pi-save"
          :loading="saving"
          @click="saveConfig"
        />
      </div>
    </template>
  </div>
</template>
