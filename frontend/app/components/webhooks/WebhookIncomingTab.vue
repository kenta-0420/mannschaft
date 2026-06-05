<script setup lang="ts">
import { z } from 'zod'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import type { IncomingWebhook } from '~/types/webhook'

const props = defineProps<{
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: string
}>()

const webhookApi = useWebhookApi()
const { success, error: showError } = useNotification()
const { formatDate } = useDatetime()

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
    const res = await webhookApi.getIncomingWebhooks(props.scopeType, props.scopeId)
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
      scopeType: props.scopeType,
      scopeId: props.scopeId,
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

onMounted(async () => {
  await loadIncomingWebhooks()
})
</script>

<template>
  <div>
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
          <span class="text-sm">{{ formatDate(data.createdAt) }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 100px">
        <template #body="{ data }">
          <Button label="削除" size="small" severity="danger" text @click="confirmDeleteIncoming(data)" />
        </template>
      </Column>
    </DataTable>

    <!-- 受信Webhook作成ダイアログ -->
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

    <!-- 受信Webhook削除確認 -->
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
  </div>
</template>
