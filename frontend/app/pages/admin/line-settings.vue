<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { getConfig, createConfig, updateConfig, deleteConfig } = useLineConfigApi()

interface LineConfig {
  id: number
  channelId: string
  webhookSecret: string | null
  botUserId: string | null
  isActive: boolean
  notificationEnabled: boolean
}

const config = ref<LineConfig | null>(null)
const loading = ref(true)
const saving = ref(false)
const form = ref({
  channelId: '',
  channelSecret: '',
  channelAccessToken: '',
  webhookSecret: '',
  notificationEnabled: true,
})

async function load() {
  loading.value = true
  try {
    const res = await getConfig(scopeType.value, scopeId.value)
    config.value = res.data as unknown as LineConfig
    if (config.value) {
      form.value = {
        channelId: config.value.channelId,
        channelSecret: '',
        channelAccessToken: '',
        webhookSecret: '',
        notificationEnabled: config.value.notificationEnabled,
      }
    }
  } catch {
    config.value = null
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value.channelId) return
  saving.value = true
  try {
    if (config.value) {
      await updateConfig(scopeType.value, scopeId.value, {
        channelId: form.value.channelId || undefined,
        channelSecret: form.value.channelSecret || undefined,
        channelAccessToken: form.value.channelAccessToken || undefined,
        webhookSecret: form.value.webhookSecret || undefined,
        notificationEnabled: form.value.notificationEnabled,
      })
    } else {
      await createConfig(scopeType.value, scopeId.value, {
        channelId: form.value.channelId,
        channelSecret: form.value.channelSecret,
        channelAccessToken: form.value.channelAccessToken,
        webhookSecret: form.value.webhookSecret || undefined,
        notificationEnabled: form.value.notificationEnabled,
      })
    }
    success('LINE設定を保存しました')
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!confirm('LINE連携設定を削除しますか？')) return
  try {
    await deleteConfig(scopeType.value, scopeId.value)
    success('設定を削除しました')
    config.value = null
    form.value = { channelId: '', channelSecret: '', channelAccessToken: '', webhookSecret: '', notificationEnabled: true }
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader title="LINE設定"><p class="text-sm text-surface-500">LINE Messaging API との連携設定</p></PageHeader>
      </div>
      <div class="flex gap-2">
        <Button v-if="config" label="削除" severity="danger" outlined size="small" @click="remove" />
        <Button label="保存" icon="pi pi-check" :loading="saving" @click="save" />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-4">
      <!-- 現在の状態 -->
      <div
        v-if="config"
        class="flex items-center gap-2 rounded-lg bg-green-50 px-4 py-3 text-green-700 dark:bg-green-950 dark:text-green-300"
      >
        <i class="pi pi-check-circle" />
        <span class="text-sm font-medium">LINE連携が設定されています（Channel ID: {{ config.channelId }}）</span>
      </div>

      <!-- API設定 -->
      <SectionCard title="API設定">
        <div class="flex flex-col gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">Channel ID <span class="text-red-500">*</span></label>
            <InputText v-model="form.channelId" class="w-full" placeholder="1234567890" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">
              Channel Secret
              <span v-if="config" class="ml-1 text-xs text-surface-400">（変更する場合のみ入力）</span>
              <span v-else class="text-red-500"> *</span>
            </label>
            <InputText v-model="form.channelSecret" type="password" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">
              Channel Access Token
              <span v-if="config" class="ml-1 text-xs text-surface-400">（変更する場合のみ入力）</span>
              <span v-else class="text-red-500"> *</span>
            </label>
            <InputText v-model="form.channelAccessToken" type="password" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">
              Webhook Secret
              <span class="ml-1 text-xs text-surface-400">（任意）</span>
            </label>
            <InputText v-model="form.webhookSecret" type="password" class="w-full" />
          </div>
        </div>
      </SectionCard>

      <!-- 通知設定 -->
      <SectionCard title="通知設定">
        <div class="flex items-center justify-between">
          <span class="text-sm">通知を有効にする</span>
          <ToggleSwitch v-model="form.notificationEnabled" />
        </div>
      </SectionCard>
    </div>
  </div>
</template>
