<script setup lang="ts">
import type {
  ConfirmableNotificationTemplate,
  ConfirmableNotificationPriority,
  CreateConfirmableNotificationRequest,
} from '~/types/confirmable'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const emit = defineEmits<{
  sent: []
}>()

const { sendNotification, listTemplates } = useConfirmableNotificationApi()
const { showError } = useNotification()
const { t } = useI18n()

const sending = ref(false)
const templates = ref<ConfirmableNotificationTemplate[]>([])

// フォームの値
const selectedTemplateId = ref<number | null>(null)
const title = ref('')
const body = ref('')
const priority = ref<ConfirmableNotificationPriority>('NORMAL')
const deadlineAt = ref<Date | null>(null)
const recipientUserIdsInput = ref('')

/** 優先度の選択肢 */
const priorityOptions = computed(() => [
  { label: t('confirmable.priority.NORMAL'), value: 'NORMAL' as const },
  { label: t('confirmable.priority.HIGH'), value: 'HIGH' as const },
  { label: t('confirmable.priority.URGENT'), value: 'URGENT' as const },
])

/** テンプレート選択肢 */
const templateOptions = computed(() => [
  { label: t('label.optional') + ' (テンプレートなし)', value: null },
  ...templates.value.map(tpl => ({ label: tpl.name, value: tpl.id })),
])

/** テンプレートが選択された時にフォームを自動填充する */
function onTemplateChange(templateId: number | null) {
  if (templateId === null) return
  const tpl = templates.value.find(t => t.id === templateId)
  if (!tpl) return
  title.value = tpl.title
  body.value = tpl.body ?? ''
  priority.value = tpl.defaultPriority
}

/** カンマ区切り入力からuserIdの配列を解析する */
function parseRecipientIds(): number[] {
  return recipientUserIdsInput.value
    .split(',')
    .map(s => s.trim())
    .filter(s => s !== '')
    .map(s => parseInt(s, 10))
    .filter(n => !isNaN(n))
}

/** 確認通知を送信する */
async function onSend() {
  const recipientUserIds = parseRecipientIds()
  if (!title.value.trim()) {
    showError(t('confirmable.title_required'))
    return
  }
  if (recipientUserIds.length === 0) {
    showError(t('confirmable.recipients_required'))
    return
  }

  sending.value = true
  try {
    const request: CreateConfirmableNotificationRequest = {
      title: title.value.trim(),
      priority: priority.value,
      recipientUserIds,
    }
    if (body.value.trim()) request.body = body.value.trim()
    if (deadlineAt.value) request.deadlineAt = deadlineAt.value.toISOString()
    if (selectedTemplateId.value !== null) request.templateId = selectedTemplateId.value

    await sendNotification(props.scopeType, props.scopeId, request)

    // フォームリセット
    title.value = ''
    body.value = ''
    priority.value = 'NORMAL'
    deadlineAt.value = null
    recipientUserIdsInput.value = ''
    selectedTemplateId.value = null

    emit('sent')
    const toast = useToast()
    toast.add({ severity: 'success', summary: t('dialog.success'), life: 3000 })
  } catch (err) {
    console.error('確認通知の送信に失敗しました', err)
    showError(t('confirmable.send_error'))
  } finally {
    sending.value = false
  }
}

/** テンプレート一覧を取得する */
async function loadTemplates() {
  try {
    const res = await listTemplates(props.scopeType, props.scopeId)
    templates.value = res.data
  } catch (err) {
    // テンプレートが取得できなくても送信フォームは使えるため、エラーログのみ
    console.error('テンプレートの取得に失敗しました', err)
  }
}

onMounted(() => loadTemplates())
</script>

<template>
  <div class="p-4">
    <h3 class="mb-4 text-base font-semibold text-surface-700">
      {{ $t('confirmable.send') }}
    </h3>

    <div class="flex flex-col gap-4">
      <!-- テンプレート選択（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          テンプレート
          <span class="ml-1 text-xs text-surface-400">（{{ $t('label.optional') }}）</span>
        </label>
        <Select
          v-model="selectedTemplateId"
          :options="templateOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          @change="() => onTemplateChange(selectedTemplateId)"
        />
      </div>

      <!-- タイトル（必須） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          タイトル
          <span class="ml-1 text-xs text-red-500">（{{ $t('label.required') }}）</span>
        </label>
        <InputText v-model="title" class="w-full" />
      </div>

      <!-- 本文（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          本文
          <span class="ml-1 text-xs text-surface-400">（{{ $t('label.optional') }}）</span>
        </label>
        <Textarea v-model="body" rows="4" class="w-full" />
      </div>

      <!-- 優先度 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">優先度</label>
        <Select
          v-model="priority"
          :options="priorityOptions"
          option-label="label"
          option-value="value"
          class="w-48"
        />
      </div>

      <!-- 確認期限（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          {{ $t('confirmable.deadline') }}
          <span class="ml-1 text-xs text-surface-400">（{{ $t('label.optional') }}）</span>
        </label>
        <DatePicker
          v-model="deadlineAt"
          show-time
          hour-format="24"
          class="w-full"
        />
      </div>

      <!-- 受信者（カンマ区切りUserID） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          受信者 UserID
          <span class="ml-1 text-xs text-red-500">（{{ $t('label.required') }}）</span>
        </label>
        <Textarea
          v-model="recipientUserIdsInput"
          :placeholder="'例: 1, 2, 3'"
          rows="2"
          class="w-full"
        />
        <p class="text-xs text-surface-400">カンマ区切りでUserIDを入力してください</p>
      </div>

      <!-- 送信ボタン -->
      <div class="mt-2">
        <Button
          :label="$t('button.submit')"
          icon="pi pi-send"
          :loading="sending"
          @click="onSend"
        />
      </div>
    </div>
  </div>
</template>
