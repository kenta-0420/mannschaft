<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type {
  BroadcastRequest,
  BroadcastResponse,
  WizardFormState,
  WizardStep,
  BulletinThreadContent,
  TimelinePostContent,
  BlogPostContent,
  TodoContent,
} from '~/types/announcement_broadcast'

const props = defineProps<{
  visible: boolean
  scopeType: AnnouncementScopeType
  scopeId: string
  isAdmin?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  success: [result: BroadcastResponse]
}>()

const { t } = useI18n()
const notification = useNotification()
const { broadcast, broadcasting } = useAnnouncementBroadcast(props.scopeType, props.scopeId)

const currentStep = ref<WizardStep>(1)

const initialFormState: WizardFormState = {
  step: 1,
  targetRole: 'MEMBERS_AND_ABOVE',
  targetTeamIds: null,
  selectedChannel: null,
  templateId: null,
  priority: 'NORMAL',
  expiresAt: null,
  content: {},
}

const formState = ref<WizardFormState>({ ...initialFormState })

/** 何かしら入力されているかどうかの判定 */
const isDirty = computed(() => {
  const c = formState.value.content as Record<string, unknown>
  const hasTitle = typeof c.title === 'string' && c.title.trim().length > 0
  const hasBody = typeof c.body === 'string' && c.body.trim().length > 0
  const hasContent = typeof c.content === 'string' && (c.content as string).trim().length > 0
  return hasTitle || hasBody || hasContent || formState.value.selectedChannel !== null
})

const stepTitle = computed(() => {
  const stepLabels: Record<WizardStep, string> = {
    1: t('announcement.step1_title'),
    2: t('announcement.step2_title'),
    3: t('announcement.step3_title'),
  }
  const stepNum = t('announcement.step_of', { current: currentStep.value, total: 3 })
  return `${t('announcement.wizard_title')} — ${stepNum} ${stepLabels[currentStep.value]}`
})

function resetWizard() {
  currentStep.value = 1
  formState.value = { ...initialFormState }
}

function handleClose() {
  if (isDirty.value) {
    // 入力があれば確認ダイアログを表示
    if (window.confirm(t('announcement.unsaved_changes_warning'))) {
      resetWizard()
      emit('update:visible', false)
    }
  } else {
    resetWizard()
    emit('update:visible', false)
  }
}

function onNext() {
  if (currentStep.value < 3) {
    currentStep.value = (currentStep.value + 1) as WizardStep
    formState.value = { ...formState.value, step: currentStep.value }
  }
}

function onBack() {
  if (currentStep.value > 1) {
    currentStep.value = (currentStep.value - 1) as WizardStep
    formState.value = { ...formState.value, step: currentStep.value }
  }
}

async function handleSubmit() {
  const { selectedChannel, targetRole, targetTeamIds, templateId, priority, expiresAt, content } = formState.value
  if (!selectedChannel) return

  const request: BroadcastRequest = {
    channel: selectedChannel,
    targetRole,
    targetTeamIds: targetTeamIds ?? null,
    templateId: templateId ?? null,
    priority,
    expiresAt: expiresAt ?? null,
    content: buildChannelContent(selectedChannel, content),
  }

  try {
    const result = await broadcast(request)
    notification.success(t('announcement.broadcast_success'))
    emit('success', result)
    resetWizard()
    emit('update:visible', false)
  } catch {
    // エラーは useAnnouncementBroadcast 内で broadcastError に格納済み
  }
}

/**
 * チャネルに応じたコンテンツオブジェクトを構築する。
 * 型は各チャネルの Interface に準拠するが、content は Partial なためキャスト。
 */
function buildChannelContent(
  channel: NonNullable<WizardFormState['selectedChannel']>,
  content: WizardFormState['content'],
): BroadcastRequest['content'] {
  const c = content as Record<string, unknown>
  if (channel === 'TIMELINE_POST') {
    return { content: String(c.content ?? '') } as TimelinePostContent
  }
  if (channel === 'BULLETIN_THREAD') {
    return {
      title: String(c.title ?? ''),
      body: String(c.body ?? ''),
      categoryId: typeof c.categoryId === 'number' ? c.categoryId : undefined,
    } as BulletinThreadContent
  }
  if (channel === 'BLOG_POST') {
    return { title: String(c.title ?? ''), body: String(c.body ?? '') } as BlogPostContent
  }
  if (channel === 'TODO') {
    return { title: String(c.title ?? '') } as TodoContent
  }
  // SCHEDULE / SURVEY は最低限 title のみ
  return { title: String(c.title ?? '') } as BulletinThreadContent
}

// preferred_channel がある場合ステップ2をスキップする
function goToNextStep() {
  if (currentStep.value === 1 && formState.value.selectedChannel !== null) {
    // テンプレートで preferred_channel が設定されていればステップ3へ
    currentStep.value = 3
    formState.value = { ...formState.value, step: 3 }
  } else {
    onNext()
  }
}

// visible が false になった際にウィザードをリセット
watch(
  () => props.visible,
  (val) => {
    if (!val) resetWizard()
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="stepTitle"
    :modal="true"
    :closable="true"
    :style="{ width: '560px', maxWidth: '95vw' }"
    :draggable="false"
    @update:visible="handleClose"
  >
    <div class="py-2">
      <BroadcastStep1Audience
        v-if="currentStep === 1"
        v-model="formState"
        :scope-type="scopeType"
        :scope-id="scopeId"
        :is-admin="isAdmin"
        @next="goToNextStep"
      />
      <BroadcastStep2Channel
        v-else-if="currentStep === 2"
        v-model="formState"
        @back="onBack"
        @next="onNext"
      />
      <BroadcastStep3Content
        v-else
        v-model="formState"
        :scope-type="scopeType"
        :scope-id="scopeId"
        :broadcasting="broadcasting"
        :is-admin="isAdmin"
        @back="onBack"
        @submit="handleSubmit"
      />
    </div>
  </Dialog>
</template>
