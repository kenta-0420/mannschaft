<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import type {
  WebhookDelivery,
  IncomingWebhook,
  ApiKeyResponse,
  ApiKeyIssueResult,
} from '~/types/webhook'
import type { WebhookEndpointResponse } from '~/composables/useWebhookApi'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const webhookApi = useWebhookApi()
const { success, error: showError } = useNotification()

const SCOPE_TYPE = 'TEAM'

// ===== タブ =====
const activeTab = ref(0)

// ===== 送信Webhook =====
const endpoints = ref<WebhookEndpointResponse[]>([])
const endpointsLoading = ref(false)

const OUTGOING_EVENT_TYPES = [
  { key: 'team.member.joined', label: 'メンバー参加' },
  { key: 'team.member.left', label: 'メンバー退出' },
  { key: 'team.event.created', label: 'イベント作成' },
  { key: 'team.schedule.updated', label: 'スケジュール更新' },
  { key: 'team.payment.received', label: '支払い受取' },
  { key: 'team.post.published', label: '投稿公開' },
  { key: 'team.form.submitted', label: 'フォーム送信' },
]

const showEndpointDialog = ref(false)
const editingEndpoint = ref<WebhookEndpointResponse | null>(null)
const endpointSaving = ref(false)
const selectedEventTypes = ref<string[]>([])

const endpointSchema = z.object({
  name: z.string().min(1, '名前は必須です').max(100, '100文字以内で入力してください'),
  url: z.string().url('有効なURLを入力してください'),
  description: z.string().max(500, '500文字以内').optional(),
  timeoutMs: z
    .number({ invalid_type_error: 'タイムアウトを入力してください' })
    .int()
    .min(1000, '1000ms以上を指定してください')
    .max(30000, '30000ms以下を指定してください')
    .optional(),
})
type EndpointForm = z.infer<typeof endpointSchema>

const {
  defineField: defineEndpointField,
  handleSubmit: handleEndpointSubmit,
  resetForm: resetEndpointForm,
  errors: endpointErrors,
} = useForm<EndpointForm>({
  validationSchema: toTypedSchema(endpointSchema),
})

const [epName, epNameAttrs] = defineEndpointField('name')
const [epUrl, epUrlAttrs] = defineEndpointField('url')
const [epDescription, epDescriptionAttrs] = defineEndpointField('description')
const [epTimeoutMs, epTimeoutMsAttrs] = defineEndpointField('timeoutMs')

// 配信ログパネル
const showDeliveryPanel = ref(false)
const selectedEndpoint = ref<WebhookEndpointResponse | null>(null)
const deliveries = ref<WebhookDelivery[]>([])
const deliveriesLoading = ref(false)
const retryingDeliveryId = ref<number | null>(null)

async function loadEndpoints() {
  endpointsLoading.value = true
  try {
    const res = await webhookApi.getEndpoints(SCOPE_TYPE, teamId)
    endpoints.value = res.data
  } catch {
    showError('Webhookエンドポイントの取得に失敗しました')
  } finally {
    endpointsLoading.value = false
  }
}

function openCreateEndpoint() {
  editingEndpoint.value = null
  selectedEventTypes.value = []
  resetEndpointForm()
  showEndpointDialog.value = true
}

function openEditEndpoint(ep: WebhookEndpointResponse) {
  editingEndpoint.value = ep
  selectedEventTypes.value = [...ep.eventTypes]
  resetEndpointForm({
    values: {
      name: ep.name,
      url: ep.url,
      description: ep.description ?? '',
      timeoutMs: ep.timeoutMs ?? undefined,
    },
  })
  showEndpointDialog.value = true
}

function toggleEventType(key: string) {
  const idx = selectedEventTypes.value.indexOf(key)
  if (idx >= 0) {
    selectedEventTypes.value.splice(idx, 1)
  } else {
    selectedEventTypes.value.push(key)
  }
}

const onSaveEndpoint = handleEndpointSubmit(async (values) => {
  endpointSaving.value = true
  try {
    if (editingEndpoint.value) {
      await webhookApi.updateEndpoint(editingEndpoint.value.id, {
        name: values.name,
        url: values.url,
        description: values.description,
        timeoutMs: values.timeoutMs,
        eventTypes: selectedEventTypes.value,
      })
      success('Webhookエンドポイントを更新しました')
    } else {
      await webhookApi.createEndpoint({
        scopeType: SCOPE_TYPE,
        scopeId: teamId,
        name: values.name,
        url: values.url,
        description: values.description,
        timeoutMs: values.timeoutMs,
        eventTypes: selectedEventTypes.value,
      })
      success('Webhookエンドポイントを作成しました')
    }
    showEndpointDialog.value = false
    await loadEndpoints()
  } catch {
    showError(editingEndpoint.value ? '更新に失敗しました' : '作成に失敗しました')
  } finally {
    endpointSaving.value = false
  }
})

async function deleteEndpoint(ep: WebhookEndpointResponse) {
  if (!confirm(`「${ep.name}」を削除しますか？`)) return
  try {
    await webhookApi.deleteEndpoint(ep.id)
    success('Webhookエンドポイントを削除しました')
    await loadEndpoints()
  } catch {
    showError('削除に失敗しました')
  }
}

async function openDeliveryPanel(ep: WebhookEndpointResponse) {
  selectedEndpoint.value = ep
  showDeliveryPanel.value = true
  deliveriesLoading.value = true
  try {
    const res = await webhookApi.getDeliveries(ep.id)
    deliveries.value = res.data
  } catch {
    showError('配信ログの取得に失敗しました')
  } finally {
    deliveriesLoading.value = false
  }
}

async function retryDelivery(delivery: WebhookDelivery) {
  retryingDeliveryId.value = delivery.id
  try {
    await webhookApi.retryDelivery(delivery.id)
    success('配信をリトライしました')
    if (selectedEndpoint.value) {
      const res = await webhookApi.getDeliveries(selectedEndpoint.value.id)
      deliveries.value = res.data
    }
  } catch {
    showError('リトライに失敗しました')
  } finally {
    retryingDeliveryId.value = null
  }
}

// ===== 受信Webhook =====
const incomingWebhooks = ref<IncomingWebhook[]>([])
const incomingLoading = ref(false)
const showIncomingDialog = ref(false)
const incomingTokenVisible = ref<Record<number, boolean>>({})
const incomingDeleting = ref<number | null>(null)
const showDeleteIncomingConfirm = ref(false)
const deletingIncoming = ref<IncomingWebhook | null>(null)

const incomingSchema = z.object({
  name: z.string().min(1, '名前は必須です').max(100, '100文字以内で入力してください'),
  description: z.string().max(500, '500文字以内').optional(),
  allowedIps: z.string().optional(),
})
type IncomingForm = z.infer<typeof incomingSchema>

const {
  defineField: defineIncomingField,
  handleSubmit: handleIncomingSubmit,
  resetForm: resetIncomingForm,
  errors: incomingErrors,
} = useForm<IncomingForm>({
  validationSchema: toTypedSchema(incomingSchema),
})

const [inName, inNameAttrs] = defineIncomingField('name')
const [inDescription, inDescriptionAttrs] = defineIncomingField('description')
const [inAllowedIps, inAllowedIpsAttrs] = defineIncomingField('allowedIps')
const incomingSaving = ref(false)

async function loadIncomingWebhooks() {
  incomingLoading.value = true
  try {
    const res = await webhookApi.getIncomingWebhooks(SCOPE_TYPE, teamId)
    incomingWebhooks.value = res.data
  } catch {
    showError('受信Webhookの取得に失敗しました')
  } finally {
    incomingLoading.value = false
  }
}

function openCreateIncoming() {
  resetIncomingForm()
  showIncomingDialog.value = true
}

const onSaveIncoming = handleIncomingSubmit(async (values) => {
  incomingSaving.value = true
  try {
    const allowedIps = values.allowedIps
      ? values.allowedIps
          .split(',')
          .map((ip) => ip.trim())
          .filter(Boolean)
      : []
    await webhookApi.createIncomingWebhook({
      scopeType: SCOPE_TYPE,
      scopeId: teamId,
      name: values.name,
      description: values.description,
      allowedIps,
    })
    success('受信Webhookを作成しました')
    showIncomingDialog.value = false
    await loadIncomingWebhooks()
  } catch {
    showError('作成に失敗しました')
  } finally {
    incomingSaving.value = false
  }
})

function confirmDeleteIncoming(wh: IncomingWebhook) {
  deletingIncoming.value = wh
  showDeleteIncomingConfirm.value = true
}

async function executeDeleteIncoming() {
  if (!deletingIncoming.value) return
  incomingDeleting.value = deletingIncoming.value.id
  try {
    await webhookApi.deleteIncomingWebhook(deletingIncoming.value.id)
    success('受信Webhookを削除しました')
    showDeleteIncomingConfirm.value = false
    deletingIncoming.value = null
    await loadIncomingWebhooks()
  } catch {
    showError('削除に失敗しました')
  } finally {
    incomingDeleting.value = null
  }
}

function toggleTokenVisibility(id: number) {
  incomingTokenVisible.value[id] = !incomingTokenVisible.value[id]
}

function maskToken(token: string) {
  return token.slice(0, 6) + '••••••••••••'
}

async function copyToClipboard(text: string, label = '') {
  try {
    await navigator.clipboard.writeText(text)
    success(`${label}をコピーしました`)
  } catch {
    showError('コピーに失敗しました')
  }
}

// ===== API キー =====
const apiKeys = ref<ApiKeyResponse[]>([])
const apiKeysLoading = ref(false)
const showApiKeyDialog = ref(false)
const showApiKeyResult = ref(false)
const issuedApiKey = ref<ApiKeyIssueResult | null>(null)
const apiKeySaving = ref(false)
const apiKeyDeleting = ref<number | null>(null)
const showDeleteApiKeyConfirm = ref(false)
const deletingApiKey = ref<ApiKeyResponse | null>(null)
const selectedPermissions = ref<string[]>([])

const API_KEY_PERMISSIONS = [
  { key: 'read:members', label: 'メンバー読み取り' },
  { key: 'write:members', label: 'メンバー書き込み' },
  { key: 'read:schedule', label: 'スケジュール読み取り' },
  { key: 'write:schedule', label: 'スケジュール書き込み' },
  { key: 'read:posts', label: '投稿読み取り' },
  { key: 'write:posts', label: '投稿書き込み' },
  { key: 'read:payments', label: '支払い読み取り' },
  { key: 'write:payments', label: '支払い書き込み' },
]

const apiKeySchema = z.object({
  name: z.string().min(1, '名前は必須です').max(100, '100文字以内で入力してください'),
  description: z.string().max(500, '500文字以内').optional(),
  expiresAt: z.date().optional().nullable(),
})
type ApiKeyForm = z.infer<typeof apiKeySchema>

const {
  defineField: defineApiKeyField,
  handleSubmit: handleApiKeySubmit,
  resetForm: resetApiKeyForm,
  errors: apiKeyErrors,
} = useForm<ApiKeyForm>({
  validationSchema: toTypedSchema(apiKeySchema),
})

const [akName, akNameAttrs] = defineApiKeyField('name')
const [akDescription, akDescriptionAttrs] = defineApiKeyField('description')
const [akExpiresAt, akExpiresAtAttrs] = defineApiKeyField('expiresAt')

async function loadApiKeys() {
  apiKeysLoading.value = true
  try {
    const res = await webhookApi.getApiKeys(SCOPE_TYPE, teamId)
    apiKeys.value = res.data
  } catch {
    showError('APIキーの取得に失敗しました')
  } finally {
    apiKeysLoading.value = false
  }
}

function openIssueApiKey() {
  selectedPermissions.value = []
  resetApiKeyForm()
  showApiKeyDialog.value = true
}

function togglePermission(key: string) {
  const idx = selectedPermissions.value.indexOf(key)
  if (idx >= 0) {
    selectedPermissions.value.splice(idx, 1)
  } else {
    selectedPermissions.value.push(key)
  }
}

const onIssueApiKey = handleApiKeySubmit(async (values) => {
  apiKeySaving.value = true
  try {
    const result = await webhookApi.issueApiKey({
      scopeType: SCOPE_TYPE,
      scopeId: teamId,
      name: values.name,
      description: values.description,
      permissions: selectedPermissions.value,
      expiresAt: values.expiresAt ? values.expiresAt.toISOString() : undefined,
    })
    issuedApiKey.value = result
    showApiKeyDialog.value = false
    showApiKeyResult.value = true
    await loadApiKeys()
  } catch {
    showError('APIキーの発行に失敗しました')
  } finally {
    apiKeySaving.value = false
  }
})

function confirmDeleteApiKey(key: ApiKeyResponse) {
  deletingApiKey.value = key
  showDeleteApiKeyConfirm.value = true
}

async function executeDeleteApiKey() {
  if (!deletingApiKey.value) return
  apiKeyDeleting.value = deletingApiKey.value.id
  try {
    await webhookApi.deleteApiKey(deletingApiKey.value.id)
    success('APIキーを削除しました')
    showDeleteApiKeyConfirm.value = false
    deletingApiKey.value = null
    await loadApiKeys()
  } catch {
    showError('削除に失敗しました')
  } finally {
    apiKeyDeleting.value = null
  }
}

// ===== 初期ロード =====
onMounted(async () => {
  await Promise.all([loadEndpoints(), loadIncomingWebhooks(), loadApiKeys()])
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="Webhook / 外部API管理" />
    </div>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0">送信Webhook</Tab>
        <Tab :value="1">受信Webhook</Tab>
        <Tab :value="2">APIキー</Tab>
      </TabList>

      <TabPanels>
        <!-- ===== タブ1: 送信Webhook ===== -->
        <TabPanel :value="0">
          <div class="mb-4 flex items-center justify-between">
            <p class="text-sm text-surface-500">外部サービスへイベントを通知するWebhookエンドポイントを管理します。</p>
            <Button label="追加" icon="pi pi-plus" @click="openCreateEndpoint" />
          </div>

          <PageLoading v-if="endpointsLoading" />

          <DataTable v-else :value="endpoints" data-key="id" striped-rows>
            <template #empty>
              <div class="py-8 text-center text-surface-500">エンドポイントがありません</div>
            </template>
            <Column header="名前 / URL">
              <template #body="{ data }">
                <div class="font-medium">{{ data.name }}</div>
                <div class="text-xs text-surface-400">{{ data.url }}</div>
              </template>
            </Column>
            <Column header="イベント数" style="width: 100px">
              <template #body="{ data }">
                <Tag :value="`${data.eventTypes.length}件`" severity="info" />
              </template>
            </Column>
            <Column header="状態" style="width: 90px">
              <template #body="{ data }">
                <Tag
                  :value="data.isActive ? '有効' : '無効'"
                  :severity="data.isActive ? 'success' : 'secondary'"
                />
              </template>
            </Column>
            <Column header="最終配信" style="width: 160px">
              <template #body="{ data }">
                <span class="text-sm">
                  {{ data.lastDeliveredAt
                    ? new Date(data.lastDeliveredAt).toLocaleString('ja-JP')
                    : '—' }}
                </span>
              </template>
            </Column>
            <Column header="失敗" style="width: 70px">
              <template #body="{ data }">
                <Tag
                  v-if="data.failureCount > 0"
                  :value="`${data.failureCount}`"
                  severity="danger"
                />
                <span v-else class="text-sm text-surface-400">0</span>
              </template>
            </Column>
            <Column header="操作" style="width: 200px">
              <template #body="{ data }">
                <div class="flex gap-1">
                  <Button label="編集" size="small" severity="info" text @click="openEditEndpoint(data)" />
                  <Button label="配信ログ" size="small" severity="secondary" text @click="openDeliveryPanel(data)" />
                  <Button label="削除" size="small" severity="danger" text @click="deleteEndpoint(data)" />
                </div>
              </template>
            </Column>
          </DataTable>

          <!-- 配信ログサイドパネル -->
          <Drawer
            v-model:visible="showDeliveryPanel"
            position="right"
            :style="{ width: '480px' }"
            :header="`配信ログ: ${selectedEndpoint?.name ?? ''}`"
          >
            <PageLoading v-if="deliveriesLoading" />
            <div v-else-if="deliveries.length === 0" class="py-8 text-center text-surface-500">
              配信ログがありません
            </div>
            <div v-else class="flex flex-col gap-3">
              <div
                v-for="d in deliveries"
                :key="d.id"
                class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
              >
                <div class="mb-2 flex items-center justify-between">
                  <span class="font-medium text-sm">{{ d.event }}</span>
                  <Tag
                    :value="d.success ? '成功' : '失敗'"
                    :severity="d.success ? 'success' : 'danger'"
                  />
                </div>
                <div class="mb-1 flex items-center gap-2 text-xs text-surface-500">
                  <span>{{ d.responseStatus ? `HTTP ${d.responseStatus}` : 'タイムアウト' }}</span>
                  <span>•</span>
                  <span>{{ new Date(d.deliveredAt).toLocaleString('ja-JP') }}</span>
                </div>
                <div v-if="!d.success" class="mt-2">
                  <Button
                    label="リトライ"
                    size="small"
                    severity="warning"
                    icon="pi pi-refresh"
                    :loading="retryingDeliveryId === d.id"
                    @click="retryDelivery(d)"
                  />
                </div>
              </div>
            </div>
          </Drawer>
        </TabPanel>

        <!-- ===== タブ2: 受信Webhook ===== -->
        <TabPanel :value="1">
          <div class="mb-4 flex items-center justify-between">
            <p class="text-sm text-surface-500">外部サービスからイベントを受け取るためのWebhook URLを管理します。</p>
            <Button label="追加" icon="pi pi-plus" @click="openCreateIncoming" />
          </div>

          <PageLoading v-if="incomingLoading" />

          <DataTable v-else :value="incomingWebhooks" data-key="id" striped-rows>
            <template #empty>
              <div class="py-8 text-center text-surface-500">受信Webhookがありません</div>
            </template>
            <Column field="name" header="名前" />
            <Column header="トークン">
              <template #body="{ data }">
                <div class="flex items-center gap-2">
                  <code class="rounded bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-800">
                    {{ incomingTokenVisible[data.id] ? data.token : maskToken(data.token) }}
                  </code>
                  <Button
                    :icon="incomingTokenVisible[data.id] ? 'pi pi-eye-slash' : 'pi pi-eye'"
                    text
                    rounded
                    size="small"
                    @click="toggleTokenVisibility(data.id)"
                  />
                  <Button
                    icon="pi pi-copy"
                    text
                    rounded
                    size="small"
                    @click="copyToClipboard(data.token, 'トークン')"
                  />
                </div>
              </template>
            </Column>
            <Column header="状態" style="width: 90px">
              <template #body="{ data }">
                <Tag
                  :value="data.isActive ? '有効' : '無効'"
                  :severity="data.isActive ? 'success' : 'secondary'"
                />
              </template>
            </Column>
            <Column header="作成日" style="width: 120px">
              <template #body="{ data }">
                <span class="text-sm">{{ new Date(data.createdAt).toLocaleDateString('ja-JP') }}</span>
              </template>
            </Column>
            <Column header="操作" style="width: 100px">
              <template #body="{ data }">
                <Button label="削除" size="small" severity="danger" text @click="confirmDeleteIncoming(data)" />
              </template>
            </Column>
          </DataTable>
        </TabPanel>

        <!-- ===== タブ3: APIキー ===== -->
        <TabPanel :value="2">
          <div class="mb-4 flex items-center justify-between">
            <p class="text-sm text-surface-500">外部アプリケーションからAPIにアクセスするためのキーを管理します。</p>
            <Button label="発行" icon="pi pi-plus" @click="openIssueApiKey" />
          </div>

          <PageLoading v-if="apiKeysLoading" />

          <DataTable v-else :value="apiKeys" data-key="id" striped-rows>
            <template #empty>
              <div class="py-8 text-center text-surface-500">APIキーがありません</div>
            </template>
            <Column field="name" header="名前" />
            <Column header="プレフィックス" style="width: 140px">
              <template #body="{ data }">
                <code class="rounded bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-800">
                  {{ data.keyPrefix }}...
                </code>
              </template>
            </Column>
            <Column header="権限スコープ">
              <template #body="{ data }">
                <div class="flex flex-wrap gap-1">
                  <Tag
                    v-for="scope in data.scopes"
                    :key="scope"
                    :value="scope"
                    severity="secondary"
                    class="text-xs"
                  />
                </div>
              </template>
            </Column>
            <Column header="有効期限" style="width: 130px">
              <template #body="{ data }">
                <span v-if="data.expiresAt" class="text-sm">
                  {{ new Date(data.expiresAt).toLocaleDateString('ja-JP') }}
                </span>
                <span v-else class="text-sm text-surface-400">無期限</span>
              </template>
            </Column>
            <Column header="最終使用" style="width: 130px">
              <template #body="{ data }">
                <span class="text-sm">
                  {{ data.lastUsedAt
                    ? new Date(data.lastUsedAt).toLocaleDateString('ja-JP')
                    : '未使用' }}
                </span>
              </template>
            </Column>
            <Column header="状態" style="width: 90px">
              <template #body="{ data }">
                <Tag
                  :value="data.isActive ? '有効' : '無効'"
                  :severity="data.isActive ? 'success' : 'secondary'"
                />
              </template>
            </Column>
            <Column header="操作" style="width: 100px">
              <template #body="{ data }">
                <Button label="削除" size="small" severity="danger" text @click="confirmDeleteApiKey(data)" />
              </template>
            </Column>
          </DataTable>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- ===== 送信Webhookダイアログ ===== -->
    <Dialog
      v-model:visible="showEndpointDialog"
      :header="editingEndpoint ? 'Webhookエンドポイント編集' : 'Webhookエンドポイント追加'"
      :style="{ width: '560px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onSaveEndpoint">
        <div>
          <label class="mb-1 block text-sm font-medium">名前 <span class="text-red-500">*</span></label>
          <InputText v-model="epName" v-bind="epNameAttrs" class="w-full" placeholder="例: Slack通知" />
          <p v-if="endpointErrors.name" class="mt-1 text-xs text-red-500">{{ endpointErrors.name }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">URL <span class="text-red-500">*</span></label>
          <InputText
            v-model="epUrl"
            v-bind="epUrlAttrs"
            class="w-full"
            placeholder="https://example.com/webhook"
          />
          <p v-if="endpointErrors.url" class="mt-1 text-xs text-red-500">{{ endpointErrors.url }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="epDescription" v-bind="epDescriptionAttrs" class="w-full" rows="2" />
          <p v-if="endpointErrors.description" class="mt-1 text-xs text-red-500">{{ endpointErrors.description }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">タイムアウト (ms)</label>
          <InputNumber
            v-model="epTimeoutMs"
            v-bind="epTimeoutMsAttrs"
            class="w-full"
            placeholder="5000"
            :min="1000"
            :max="30000"
          />
          <p v-if="endpointErrors.timeoutMs" class="mt-1 text-xs text-red-500">{{ endpointErrors.timeoutMs }}</p>
        </div>
        <div>
          <label class="mb-2 block text-sm font-medium">イベント種別</label>
          <div class="rounded-lg border border-surface-300 p-3 dark:border-surface-700">
            <div
              v-for="evt in OUTGOING_EVENT_TYPES"
              :key="evt.key"
              class="flex items-center gap-2 py-1"
            >
              <Checkbox
                :input-id="`evt-${evt.key}`"
                :model-value="selectedEventTypes.includes(evt.key)"
                :binary="true"
                @update:model-value="toggleEventType(evt.key)"
              />
              <label :for="`evt-${evt.key}`" class="cursor-pointer text-sm">{{ evt.label }}</label>
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showEndpointDialog = false" />
          <Button
            :label="editingEndpoint ? '更新' : '作成'"
            type="submit"
            :loading="endpointSaving"
          />
        </div>
      </form>
    </Dialog>

    <!-- ===== 受信Webhook作成ダイアログ ===== -->
    <Dialog
      v-model:visible="showIncomingDialog"
      header="受信Webhook追加"
      :style="{ width: '480px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onSaveIncoming">
        <div>
          <label class="mb-1 block text-sm font-medium">名前 <span class="text-red-500">*</span></label>
          <InputText v-model="inName" v-bind="inNameAttrs" class="w-full" placeholder="例: GitHub連携" />
          <p v-if="incomingErrors.name" class="mt-1 text-xs text-red-500">{{ incomingErrors.name }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="inDescription" v-bind="inDescriptionAttrs" class="w-full" rows="2" />
          <p v-if="incomingErrors.description" class="mt-1 text-xs text-red-500">{{ incomingErrors.description }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">許可IP（カンマ区切り）</label>
          <InputText
            v-model="inAllowedIps"
            v-bind="inAllowedIpsAttrs"
            class="w-full"
            placeholder="192.168.1.1, 10.0.0.0/24"
          />
          <p class="mt-1 text-xs text-surface-400">空の場合はすべてのIPを許可します</p>
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showIncomingDialog = false" />
          <Button label="作成" type="submit" :loading="incomingSaving" />
        </div>
      </form>
    </Dialog>

    <!-- ===== 受信Webhook削除確認 ===== -->
    <Dialog
      v-model:visible="showDeleteIncomingConfirm"
      header="受信Webhookの削除"
      :style="{ width: '400px' }"
      modal
    >
      <p class="mb-4">
        「<strong>{{ deletingIncoming?.name }}</strong>」を削除しますか？この操作は取り消せません。
      </p>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" text @click="showDeleteIncomingConfirm = false" />
        <Button
          label="削除"
          severity="danger"
          :loading="incomingDeleting !== null"
          @click="executeDeleteIncoming"
        />
      </div>
    </Dialog>

    <!-- ===== APIキー発行ダイアログ ===== -->
    <Dialog
      v-model:visible="showApiKeyDialog"
      header="APIキー発行"
      :style="{ width: '560px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="onIssueApiKey">
        <div>
          <label class="mb-1 block text-sm font-medium">名前 <span class="text-red-500">*</span></label>
          <InputText v-model="akName" v-bind="akNameAttrs" class="w-full" placeholder="例: 外部連携システム" />
          <p v-if="apiKeyErrors.name" class="mt-1 text-xs text-red-500">{{ apiKeyErrors.name }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="akDescription" v-bind="akDescriptionAttrs" class="w-full" rows="2" />
          <p v-if="apiKeyErrors.description" class="mt-1 text-xs text-red-500">{{ apiKeyErrors.description }}</p>
        </div>
        <div>
          <label class="mb-2 block text-sm font-medium">権限スコープ</label>
          <div class="grid grid-cols-2 gap-2 rounded-lg border border-surface-300 p-3 dark:border-surface-700">
            <div
              v-for="perm in API_KEY_PERMISSIONS"
              :key="perm.key"
              class="flex items-center gap-2"
            >
              <Checkbox
                :input-id="`perm-${perm.key}`"
                :model-value="selectedPermissions.includes(perm.key)"
                :binary="true"
                @update:model-value="togglePermission(perm.key)"
              />
              <label :for="`perm-${perm.key}`" class="cursor-pointer text-sm">{{ perm.label }}</label>
            </div>
          </div>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">有効期限（任意）</label>
          <DatePicker
            v-model="akExpiresAt"
            v-bind="akExpiresAtAttrs"
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
            :min-date="new Date()"
          />
        </div>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" text @click="showApiKeyDialog = false" />
          <Button label="発行" type="submit" :loading="apiKeySaving" />
        </div>
      </form>
    </Dialog>

    <!-- ===== APIキー発行結果モーダル ===== -->
    <Dialog
      v-model:visible="showApiKeyResult"
      header="APIキーが発行されました"
      :style="{ width: '500px' }"
      modal
      :closable="false"
    >
      <div class="flex flex-col gap-4">
        <Message severity="warn" :closable="false">
          このキーは一度しか表示されません。必ずコピーして安全な場所に保管してください。
        </Message>
        <div>
          <label class="mb-1 block text-sm font-medium">APIキー</label>
          <div class="flex items-center gap-2">
            <code class="flex-1 break-all rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-800">
              {{ issuedApiKey?.fullKey }}
            </code>
            <Button
              icon="pi pi-copy"
              severity="secondary"
              @click="copyToClipboard(issuedApiKey?.fullKey ?? '', 'APIキー')"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="閉じる" @click="showApiKeyResult = false; issuedApiKey = null" />
      </template>
    </Dialog>

    <!-- ===== APIキー削除確認 ===== -->
    <Dialog
      v-model:visible="showDeleteApiKeyConfirm"
      header="APIキーの削除"
      :style="{ width: '400px' }"
      modal
    >
      <p class="mb-4">
        「<strong>{{ deletingApiKey?.name }}</strong>」を削除しますか？このキーを使用している連携は動作しなくなります。
      </p>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" text @click="showDeleteApiKeyConfirm = false" />
        <Button
          label="削除"
          severity="danger"
          :loading="apiKeyDeleting !== null"
          @click="executeDeleteApiKey"
        />
      </div>
    </Dialog>
  </div>
</template>
