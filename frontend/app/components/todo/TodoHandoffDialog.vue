<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
import type { HandoffRequest } from '~/types/todoHandoff'
import type { TodoStatusLabel } from '~/types/todoStatusLabel'

/**
 * F02.3.1 Phase 2 — TODO キャッチボール（引き渡し）ダイアログ。
 *
 * 宛先メンバー（複数）+ ステータスラベル + メッセージを入力し、
 * 確認画面を経由して POST /handoff を呼び出す。
 */
const props = defineProps<{
  visible: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
  todoId: number
  todoTitle: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'handoffComplete': []
}>()

const { t } = useI18n()
const todoApi = useTodoApi()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const labelApi = useTodoStatusLabelApi()
const notification = useNotification()

const allLabels = ref<TodoStatusLabel[]>([])

const members = ref<MemberResponse[]>([])
const loadingMembers = ref(false)
const selectedUserIds = ref<number[]>([])
const selectedLabelId = ref<number | null>(null)
const selectedLabelName = ref<string>('')
const message = ref<string>('')
const submitting = ref(false)
const showConfirm = ref(false)

const labelScopeType = computed<'TEAM' | 'ORGANIZATION'>(() =>
  props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION',
)

async function loadMembers() {
  loadingMembers.value = true
  try {
    if (props.scopeType === 'team') {
      const res = await teamApi.getMembers(props.scopeId, { page: 0, size: 200 })
      members.value = res.data
    } else {
      const res = await orgApi.getMembers(props.scopeId, { page: 0, size: 200 })
      members.value = res.data
    }
  } catch {
    notification.error(t('handoff.error.loadMembers'))
  } finally {
    loadingMembers.value = false
  }
}

async function loadLabels() {
  try {
    const scope = props.scopeType === 'team' ? 'team' : 'organization'
    const res = await labelApi.listLabels(scope, props.scopeId)
    allLabels.value = res.data
  } catch {
    // 通知だけ出して継続。Selectは空のまま
    notification.error(t('handoff.error.loadLabels'))
  }
}

watch(() => props.visible, (open) => {
  if (open) {
    selectedUserIds.value = []
    selectedLabelId.value = null
    selectedLabelName.value = ''
    message.value = ''
    showConfirm.value = false
    void loadMembers()
    void loadLabels()
  }
})

// ラベル選択変更時、ラベル名をローカルに反映
watch(selectedLabelId, (id) => {
  const found = allLabels.value.find((l) => l.id === id)
  selectedLabelName.value = found?.name ?? ''
})

const selectedUserNames = computed(() => {
  return members.value
    .filter((m) => selectedUserIds.value.includes(m.userId))
    .map((m) => m.displayName)
})

const confirmBody = computed(() => {
  if (selectedUserNames.value.length === 0) return ''
  const namesStr = selectedUserNames.value.length === 1
    ? t('handoff.confirm.recipientSingle', { name: selectedUserNames.value[0] })
    : t('handoff.confirm.recipientWithOthers', {
      name: selectedUserNames.value[0],
      count: selectedUserNames.value.length - 1,
    })
  return t('handoff.confirm.body', {
    names: namesStr,
    title: props.todoTitle,
    label: selectedLabelName.value,
  })
})

function openConfirm() {
  if (selectedUserIds.value.length === 0) {
    notification.warn(t('handoff.error.recipientsRequired'))
    return
  }
  if (selectedLabelId.value === null) {
    notification.warn(t('handoff.error.labelRequired'))
    return
  }
  showConfirm.value = true
}

async function execute() {
  if (selectedLabelId.value === null) return
  submitting.value = true
  try {
    const body: HandoffRequest = {
      toUserIds: selectedUserIds.value,
      statusLabelId: selectedLabelId.value,
      message: message.value || null,
    }
    await todoApi.handoff(props.scopeType, props.scopeId, props.todoId, body)
    notification.success(t('handoff.success'))
    emit('handoffComplete')
    emit('update:visible', false)
  } catch (e: unknown) {
    const errObj = e as { data?: { error?: { code?: string } } }
    const code = errObj?.data?.error?.code
    // F02.3.1 後続 C-6: サーバ側のエラーコードを UI に適切にマッピング。
    // 番号方式 (TODO_xxx / COMMON_xxx) と固定文字列方式 (MILESTONE_LOCKED) の両方に対応。
    if (code === 'TODO_080') {
      // HANDOFF_NOT_ALLOWED_FOR_PERSONAL — 個人 TODO への handoff
      notification.error(t('handoff.error.notAllowedPersonal'))
    } else if (code === 'TODO_081') {
      // HANDOFF_RECIPIENT_NOT_MEMBER — 宛先がスコープのメンバーではない
      notification.error(t('handoff.error.invalidRecipient'))
    } else if (code === 'TODO_074') {
      // LABEL_SCOPE_MISMATCH — ラベルが TODO のスコープ・SYSTEM のいずれでもない
      notification.error(t('handoff.error.labelScopeMismatch'))
    } else if (code === 'MILESTONE_LOCKED') {
      // F02.7 マイルストーンロック中の TODO への handoff
      notification.error(t('handoff.error.milestoneLocked'))
    } else if (code === 'COMMON_001') {
      // VALIDATION_ERROR — Bean Validation 失敗
      notification.error(t('handoff.error.validation'))
    } else if (code === 'TODO_010') {
      // TODO_NOT_FOUND — IDOR 対策で 404
      notification.error(t('handoff.error.notFound'))
    } else {
      notification.error(t('handoff.error.generic'))
    }
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('handoff.dialog.title')"
    modal
    :style="{ width: '480px' }"
    :closable="!submitting"
    @update:visible="(v) => emit('update:visible', v)"
  >
    <div v-if="!showConfirm" class="space-y-4">
      <!-- 宛先 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('handoff.field.recipients') }}</label>
        <MultiSelect
          v-model="selectedUserIds"
          :options="members"
          option-label="displayName"
          option-value="userId"
          :loading="loadingMembers"
          :placeholder="t('handoff.field.recipientsPlaceholder')"
          filter
          class="w-full"
        />
      </div>

      <!-- ステータスラベル -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('handoff.field.statusLabel') }}</label>
        <TodoStatusLabelSelect
          :scope-type="labelScopeType"
          :scope-id="scopeId"
          :model-value="selectedLabelId"
          @update:model-value="(v) => (selectedLabelId = v)"
        />
      </div>

      <!-- メッセージ -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('handoff.field.message') }}</label>
        <Textarea
          v-model="message"
          rows="3"
          maxlength="500"
          class="w-full"
          :placeholder="t('handoff.field.messagePlaceholder')"
        />
        <p class="mt-1 text-right text-xs text-surface-500">{{ message.length }}/500</p>
      </div>

      <div class="flex justify-end gap-2">
        <Button :label="t('common.cancel')" severity="secondary" outlined @click="close" />
        <Button :label="t('handoff.confirm.button')" icon="pi pi-send" @click="openConfirm" />
      </div>
    </div>

    <!-- 確認画面 -->
    <div v-else class="space-y-4">
      <div class="rounded-lg border border-surface-300 bg-surface-50 p-4 dark:border-surface-600 dark:bg-surface-900">
        <p class="text-sm text-surface-700 dark:text-surface-300">{{ confirmBody }}</p>
        <p v-if="message" class="mt-2 whitespace-pre-wrap rounded-md border border-surface-200 bg-surface-0 p-2 text-xs text-surface-600 dark:border-surface-700 dark:bg-surface-800">
          💬 {{ message }}
        </p>
      </div>
      <div class="flex justify-end gap-2">
        <Button :label="t('common.cancel')" severity="secondary" outlined :disabled="submitting" @click="showConfirm = false" />
        <Button :label="t('handoff.confirm.button')" icon="pi pi-send" :loading="submitting" @click="execute" />
      </div>
    </div>
  </Dialog>
</template>
