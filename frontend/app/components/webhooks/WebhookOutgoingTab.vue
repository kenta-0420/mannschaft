<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import type { WebhookDelivery } from '~/types/webhook'
import type { WebhookEndpointResponse } from '~/composables/useWebhookApi'

const props = defineProps<{
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: string
}>()

const webhookApi = useWebhookApi()
const { success, error: showError } = useNotification()
const { formatDateTime } = useDatetime()

const scopePrefix = computed(() => props.scopeType === 'TEAM' ? 'team' : 'organization')

const OUTGOING_EVENT_TYPES = computed(() => [
  { key: `${scopePrefix.value}.member.joined`, label: 'メンバー参加' },
  { key: `${scopePrefix.value}.member.left`, label: 'メンバー退出' },
  { key: `${scopePrefix.value}.event.created`, label: 'イベント作成' },
  { key: `${scopePrefix.value}.schedule.updated`, label: 'スケジュール更新' },
  { key: `${scopePrefix.value}.payment.received`, label: '支払い受取' },
  { key: `${scopePrefix.value}.post.published`, label: '投稿公開' },
  { key: `${scopePrefix.value}.form.submitted`, label: 'フォーム送信' },
])

// ===== 送信Webhook =====
const endpoints = ref<WebhookEndpointResponse[]>([])
const endpointsLoading = ref(false)

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
    const res = await webhookApi.getEndpoints(props.scopeType, props.scopeId)
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
        scopeType: props.scopeType,
        scopeId: props.scopeId,
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

onMounted(async () => {
  await loadEndpoints()
})
</script>

<template>
  <div>
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
              ? formatDateTime(data.lastDeliveredAt)
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
            <span>{{ formatDateTime(d.deliveredAt) }}</span>
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

    <!-- 送信Webhookダイアログ -->
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
  </div>
</template>
