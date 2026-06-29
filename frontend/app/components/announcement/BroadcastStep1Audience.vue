<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type { AnnouncementTemplate, AnnouncementTemplateRequest, BroadcastTargetRole, WizardFormState } from '~/types/announcement_broadcast'

const props = defineProps<{
  modelValue: WizardFormState
  scopeType: AnnouncementScopeType
  scopeId: string
  isAdmin?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WizardFormState]
  next: []
}>()

const { t } = useI18n()
const notification = useNotification()
const { getTeamsInOrg } = useOrganizationApi()
const { createTemplate, saving } = useAnnouncementTemplates(props.scopeType, props.scopeId)

const targetRole = computed({
  get() {
    return props.modelValue.targetRole
  },
  set(value: BroadcastTargetRole) {
    emit('update:modelValue', { ...props.modelValue, targetRole: value })
  },
})

const targetRoleOptions: { label: string; value: BroadcastTargetRole }[] = [
  { label: t('announcement.target_role_members_only'), value: 'MEMBERS_ONLY' },
  { label: t('announcement.target_role_supporters_and_above'), value: 'SUPPORTERS_AND_ABOVE' },
  { label: t('announcement.target_role_public'), value: 'PUBLIC' },
]

/** 「すべてのチーム」チェックボックスの状態 */
const allTeams = computed({
  get() {
    return props.modelValue.targetTeamIds === null
  },
  set(value: boolean) {
    if (value) {
      emit('update:modelValue', { ...props.modelValue, targetTeamIds: null })
    } else {
      emit('update:modelValue', { ...props.modelValue, targetTeamIds: [] })
    }
  },
})

/** 組織配下のチーム一覧（ORGANIZATION スコープのみ使用。id と name のみ利用） */
const orgTeams = ref<Array<{ id: string; name: string }>>([])

// scopeType === 'ORGANIZATION' のときにチーム一覧を取得
watchEffect(async () => {
  if (props.scopeType === 'ORGANIZATION') {
    const res = await getTeamsInOrg(props.scopeId)
    orgTeams.value = res.data.map(t => ({ id: String(t.id), name: t.name }))
  }
})

/** 個別チームのチェック状態 */
function isTeamChecked(teamId: string): boolean {
  return props.modelValue.targetTeamIds?.includes(teamId) ?? false
}

/** 個別チームのチェックを切り替える */
function toggleTeam(teamId: string, checked: boolean) {
  const current = props.modelValue.targetTeamIds ?? []
  const next = checked
    ? [...current, teamId]
    : current.filter((id) => id !== teamId)
  emit('update:modelValue', { ...props.modelValue, targetTeamIds: next })
}

/** テンプレート適用時にフォーム状態を上書き */
function onTemplateApply(template: AnnouncementTemplate) {
  emit('update:modelValue', {
    ...props.modelValue,
    targetRole: template.targetRole,
    targetTeamIds: template.targetTeamIds,
    // preferred_channel が設定されていればステップ2のチャネル初期選択に使う
    selectedChannel: template.preferredChannel ?? props.modelValue.selectedChannel,
    templateId: template.id,
  })
}

const templateId = computed({
  get() {
    return props.modelValue.templateId
  },
  set(value: number | null) {
    emit('update:modelValue', { ...props.modelValue, templateId: value })
  },
})

// ── 現在の範囲をテンプレートとして保存（ADMIN / DEPUTY_ADMIN のみ表示） ──
const templateSelector = ref<{ refresh: () => Promise<void> } | null>(null)
const showSaveForm = ref(false)
const newTemplateName = ref('')

function openSaveForm() {
  newTemplateName.value = ''
  showSaveForm.value = true
}

function cancelSaveForm() {
  showSaveForm.value = false
  newTemplateName.value = ''
}

async function saveAsTemplate() {
  const name = newTemplateName.value.trim()
  if (name.length === 0) return

  const request: AnnouncementTemplateRequest = {
    name,
    targetRole: props.modelValue.targetRole,
    targetTeamIds: props.modelValue.targetTeamIds,
    preferredChannel: props.modelValue.selectedChannel,
  }

  try {
    await createTemplate(request)
    notification.success(t('announcement.template_save_success'))
    showSaveForm.value = false
    newTemplateName.value = ''
    // 選択リストへ即時反映するため、セレクター側の一覧を再取得する
    await templateSelector.value?.refresh()
  } catch {
    // createTemplate は失敗時に文字列を throw する（上限超過・認可エラー等）
    notification.error(t('announcement.template_save_failed'))
  }
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- 対象ロール -->
    <div>
      <p class="mb-3 font-medium text-surface-700 dark:text-surface-300">
        {{ t('announcement.target_role_label') }}
      </p>
      <div class="flex flex-col gap-2">
        <div v-for="opt in targetRoleOptions" :key="opt.value" class="flex items-center gap-2">
          <RadioButton
            v-model="targetRole"
            :input-id="`target_role_${opt.value}`"
            :value="opt.value"
          />
          <label :for="`target_role_${opt.value}`" class="cursor-pointer">{{ opt.label }}</label>
        </div>
      </div>
    </div>

    <!-- 組織告知の場合のみ: 対象チーム選択 -->
    <div v-if="scopeType === 'ORGANIZATION'" class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <p class="mb-3 font-medium text-surface-700 dark:text-surface-300">
        {{ t('announcement.target_teams_label') }}
      </p>
      <div class="flex flex-col gap-2">
        <div class="flex items-center gap-2">
          <Checkbox v-model="allTeams" input-id="all_teams" :binary="true" />
          <label for="all_teams" class="cursor-pointer font-medium">
            {{ t('announcement.target_teams_all') }}
          </label>
        </div>
        <!-- チーム個別選択（allTeams=false のときのみ表示） -->
        <template v-if="!allTeams && orgTeams.length > 0">
          <div
            v-for="team in orgTeams"
            :key="team.id"
            class="flex items-center gap-2 pl-6"
          >
            <Checkbox
              :input-id="`team_${team.id}`"
              :model-value="isTeamChecked(team.id)"
              :binary="true"
              @update:model-value="(val) => toggleTeam(team.id, val as boolean)"
            />
            <label :for="`team_${team.id}`" class="cursor-pointer text-sm">
              {{ team.name }}
            </label>
          </div>
        </template>
      </div>
    </div>

    <!-- テンプレート選択 -->
    <div class="flex flex-col gap-3">
      <BroadcastTemplateSelector
        ref="templateSelector"
        v-model="templateId"
        :scope-type="scopeType"
        :scope-id="scopeId"
        @apply="onTemplateApply"
      />

      <!-- 現在の範囲をテンプレートとして保存（ADMIN / DEPUTY_ADMIN のみ） -->
      <div v-if="isAdmin">
        <Button
          v-if="!showSaveForm"
          :label="t('announcement.template_create')"
          icon="pi pi-save"
          severity="secondary"
          text
          size="small"
          @click="openSaveForm"
        />
        <div v-else class="flex items-center gap-2">
          <InputText
            v-model="newTemplateName"
            :placeholder="t('announcement.template_name_placeholder')"
            class="flex-1"
            :maxlength="100"
            @keyup.enter="saveAsTemplate"
          />
          <Button
            :label="$t('button.save')"
            icon="pi pi-check"
            size="small"
            :disabled="newTemplateName.trim().length === 0"
            :loading="saving"
            @click="saveAsTemplate"
          />
          <Button
            :label="$t('button.cancel')"
            severity="secondary"
            text
            size="small"
            :disabled="saving"
            @click="cancelSaveForm"
          />
        </div>
      </div>
    </div>

    <!-- ナビゲーション -->
    <div class="flex justify-end pt-2">
      <Button :label="$t('button.next')" icon="pi pi-arrow-right" icon-pos="right" @click="emit('next')" />
    </div>
  </div>
</template>
