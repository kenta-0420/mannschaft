<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import type { ApiKeyResponse, ApiKeyIssueResult } from '~/types/webhook'

const props = defineProps<{
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: string
}>()

const webhookApi = useWebhookApi()
const { success, error: showError } = useNotification()
const { formatDate } = useDatetime()

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
    const res = await webhookApi.getApiKeys(props.scopeType, props.scopeId)
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
      scopeType: props.scopeType,
      scopeId: props.scopeId,
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

async function copyToClipboard(text: string, label = '') {
  try {
    await navigator.clipboard.writeText(text)
    success(`${label}をコピーしました`)
  } catch {
    showError('コピーに失敗しました')
  }
}

onMounted(async () => {
  await loadApiKeys()
})
</script>

<template>
  <div>
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
            {{ formatDate(data.expiresAt) }}
          </span>
          <span v-else class="text-sm text-surface-400">無期限</span>
        </template>
      </Column>
      <Column header="最終使用" style="width: 130px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ data.lastUsedAt
              ? formatDate(data.lastUsedAt)
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

    <!-- APIキー発行ダイアログ -->
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

    <!-- APIキー発行結果モーダル -->
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

    <!-- APIキー削除確認 -->
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
