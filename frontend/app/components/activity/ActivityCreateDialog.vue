<script setup lang="ts">
// 活動記録 作成ダイアログ（ADHD配慮・二段フロー対応）
//
// 二段フロー: タイトル + 活動日のみで DRAFT 作成 → 詳細画面で詳細記入 → 公開
// BE CreateDraftActivityRequest は title + activityDate のみで最小保存が可能。
// テンプレ・カスタムフィールドは後付けで更新できる。
// useFormDraft で入力途中を localStorage に自動保存し、ダイアログを閉じても復元する。
//
// 従来の全項目一括保存フロー（テンプレ+フィールド+即PUBLISHED）も維持する。
// フッターに「下書き保存」「追加」の2ボタンを設ける。
import type {
  ActivityTemplate,
  ActivityTemplateField,
  CreateActivityRequestBody,
} from '~/types/activity'
import type { CreateDraftActivityRequestBody } from '~/composables/useActivityApi'
import {
  buildActivityFieldValues,
  canSubmitActivity,
  parseSelectOptions,
  toYmd,
  type ActivityFieldValue,
} from '~/utils/activityFields'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  /** URL slug（数値文字列でも可）。open 時に数値 DB id へ解決する */
  scopeId: string
}>()

// defineModel が内部で 'update:visible' を発行する
const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{ created: [] }>()

const { t } = useI18n()
const { getTemplates, createActivity, createDraftActivity } = useActivityApi()
const { resolveScopeId } = useActivityScopeId()
const { success: showSuccess, error: showError } = useNotification()
const authStore = useAuthStore()

// === 状態 ===
const loadingTemplates = ref(false)
const submitting = ref(false)
const resolvedScopeId = ref<number | null>(null)
const templates = ref<ActivityTemplate[]>([])
const selectedTemplateId = ref<number | null>(null)

// 共通入力
const title = ref('')
const activityDate = ref<Date | null>(null)
const description = ref('')
const visibility = ref<'PUBLIC' | 'MEMBERS_ONLY'>('MEMBERS_ONLY')

// カスタムフィールド値（キーは fieldKey）
const fieldInputs = ref<Record<string, ActivityFieldValue>>({})

const selectedTemplate = computed<ActivityTemplate | null>(
  () => templates.value.find((tp) => tp.id === selectedTemplateId.value) ?? null,
)

const templateFields = computed<ActivityTemplateField[]>(() =>
  [...(selectedTemplate.value?.fields ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
)

const visibilityOptions = computed(() => [
  { label: t('activity.create.visibilityMembersOnly'), value: 'MEMBERS_ONLY' as const },
  { label: t('activity.create.visibilityPublic'), value: 'PUBLIC' as const },
])

// テンプレートを管理するページへの導線（team のみ専用ページが存在する）
const templatesPagePath = computed(() =>
  props.scopeType === 'TEAM' ? `/teams/${props.scopeId}/activity-templates` : null,
)

// === バリデーション（純ロジックは utils/activityFields に切り出し・unit テスト対象） ===
const canSubmit = computed(() =>
  canSubmitActivity({
    templateId: selectedTemplateId.value,
    title: title.value,
    activityDate: activityDate.value,
    fields: templateFields.value,
    inputs: fieldInputs.value,
  }),
)

/** タイトル + 活動日のみで DRAFT 作成可能かどうか */
const canSaveDraft = computed(() => title.value.trim().length > 0 && activityDate.value !== null)

// === useFormDraft（ADHD配慮・自動保存）===
// キーにuserId・scopeType・scopeIdを含めてスコープ間の衝突を防ぐ
const draftKey = computed(
  () =>
    `activity-create-draft-${authStore.currentUser?.id ?? 'guest'}-${props.scopeType}-${props.scopeId}`,
)

interface ActivityDraftShape {
  title: string
  activityDate: string | null
  description: string
  visibility: 'PUBLIC' | 'MEMBERS_ONLY'
  selectedTemplateId: number | null
}

// source にフォーム全体の computed を渡して自動保存
const formSnapshot = computed<ActivityDraftShape>(() => ({
  title: title.value,
  activityDate: activityDate.value ? activityDate.value.toISOString() : null,
  description: description.value,
  visibility: visibility.value,
  selectedTemplateId: selectedTemplateId.value,
}))

const { clear: clearDraft, restore: restoreDraft, savedFlash } = useFormDraft<ActivityDraftShape>(
  draftKey.value,
  { source: formSnapshot, debounceMs: 1000, flashMs: 2000 },
)

// 復元済みフラッシュ（ダイアログを開いた直後に1回だけ復元トースト表示）
const restoredFlash = ref(false)

// === open 時のロード ===
async function loadTemplates() {
  loadingTemplates.value = true
  try {
    resolvedScopeId.value = await resolveScopeId(props.scopeType, props.scopeId)
    if (resolvedScopeId.value === null) {
      showError(t('activity.create.scopeResolveError'))
      templates.value = []
      return
    }
    const res = await getTemplates(props.scopeType, String(resolvedScopeId.value))
    templates.value = res.data ?? []
    // テンプレが1件のときは自動選択
    if (templates.value.length === 1) {
      selectedTemplateId.value = templates.value[0]!.id
    }
  } catch {
    showError(t('activity.create.templatesLoadError'))
    templates.value = []
  } finally {
    loadingTemplates.value = false
  }
}

// テンプレ選択が変わったらフィールド入力をリセットし、既定 visibility を反映する
watch(selectedTemplate, (tpl) => {
  fieldInputs.value = {}
  visibility.value = tpl?.defaultVisibility ?? 'MEMBERS_ONLY'
})

function resetForm() {
  templates.value = []
  selectedTemplateId.value = null
  resolvedScopeId.value = null
  title.value = ''
  activityDate.value = null
  description.value = ''
  visibility.value = 'MEMBERS_ONLY'
  fieldInputs.value = {}
}

watch(visible, (val) => {
  if (!val) return
  resetForm()
  loadTemplates()
  // ダイアログが開くたびに下書き復元を試みる
  const saved = restoreDraft()
  if (saved) {
    title.value = saved.title ?? ''
    activityDate.value = saved.activityDate ? new Date(saved.activityDate) : null
    description.value = saved.description ?? ''
    visibility.value = saved.visibility ?? 'MEMBERS_ONLY'
    // savedのselectedTemplateIdはテンプレートロード完了後に適用
    // （loadTemplatesが非同期なため、ロード完了watchで適用する）
    restoredFlash.value = true
    setTimeout(() => {
      restoredFlash.value = false
    }, 3000)
  }
})

// === 下書き保存（DRAFT作成）===
async function saveDraft() {
  if (!canSaveDraft.value || resolvedScopeId.value === null) return
  submitting.value = true
  try {
    const body: CreateDraftActivityRequestBody = {
      title: title.value.trim(),
      activityDate: toYmd(activityDate.value!),
      templateId: selectedTemplateId.value ?? undefined,
      description: description.value.trim() || undefined,
      visibility: visibility.value,
    }
    await createDraftActivity(props.scopeType, resolvedScopeId.value, body)
    clearDraft()
    showSuccess(t('activity.create.draftSuccess'))
    visible.value = false
    await nextTick()
    emit('created')
  } catch {
    showError(t('activity.create.saveError'))
  } finally {
    submitting.value = false
  }
}

// === 送信（全項目入力後・即PUBLISHED作成）===
async function submit() {
  if (!canSubmit.value || resolvedScopeId.value === null || selectedTemplateId.value === null) return
  submitting.value = true
  let success = false
  try {
    const body: CreateActivityRequestBody = {
      templateId: selectedTemplateId.value,
      title: title.value.trim(),
      activityDate: toYmd(activityDate.value!),
      description: description.value.trim() || undefined,
      visibility: visibility.value,
      fieldValues: buildActivityFieldValues(templateFields.value, fieldInputs.value),
    }
    await createActivity(props.scopeType, resolvedScopeId.value, body)
    clearDraft()
    success = true
  } catch {
    // 失敗時はダイアログを維持し入力を保持する（症状を握りつぶさず明示）
    showError(t('activity.create.saveError'))
  } finally {
    submitting.value = false
  }

  if (success) {
    visible.value = false
    showSuccess(t('activity.create.saveSuccess'))
    await nextTick()
    emit('created')
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('activity.create.dialogTitle')"
    :style="{ width: '640px' }"
    :breakpoints="{ '960px': '90vw' }"
    @hide="resetForm"
  >
    <div class="flex flex-col gap-4" data-testid="activity-create-dialog">
      <PageLoading v-if="loadingTemplates" size="32px" />

      <!-- テンプレ未作成 -->
      <div
        v-else-if="templates.length === 0"
        class="flex flex-col items-center gap-3 py-8 text-center"
        data-testid="activity-no-templates"
      >
        <i class="pi pi-file-edit text-4xl text-surface-300" />
        <p class="font-medium">{{ t('activity.create.noTemplatesTitle') }}</p>
        <p class="text-sm text-surface-500">{{ t('activity.create.noTemplatesBody') }}</p>
        <NuxtLink v-if="templatesPagePath" :to="templatesPagePath">
          <Button
            :label="t('activity.create.goToTemplates')"
            icon="pi pi-arrow-right"
            outlined
            size="small"
            data-testid="activity-go-to-templates"
            @click="visible = false"
          />
        </NuxtLink>
      </div>

      <!-- 入力フォーム -->
      <template v-else>
        <!-- 復元フラッシュ -->
        <div
          v-if="restoredFlash"
          class="flex items-center gap-2 rounded-lg bg-blue-50 px-3 py-2 text-sm text-blue-700 dark:bg-blue-900/30 dark:text-blue-200"
          data-testid="activity-draft-restored-flash"
        >
          <i class="pi pi-history" />
          {{ t('activity.create.draftRestored') }}
        </div>

        <!-- 下書き保存済みフラッシュ -->
        <div
          v-if="savedFlash"
          class="flex items-center gap-2 rounded-lg bg-surface-50 px-3 py-2 text-xs text-surface-500 dark:bg-surface-800 dark:text-surface-400"
          data-testid="activity-draft-saved-flash"
        >
          <i class="pi pi-save" />
          {{ t('activity.create.draftSaved') }}
        </div>

        <!-- ADHD配慮ヒント: タイトル+日付だけで保存できる旨を案内 -->
        <div class="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-600 dark:bg-blue-900/20 dark:text-blue-300">
          <i class="pi pi-lightbulb mt-0.5 shrink-0" />
          <span>{{ t('activity.create.draftHint') }}</span>
        </div>

        <!-- テンプレ選択 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('activity.create.templateLabel') }}
          </label>
          <Select
            v-model="selectedTemplateId"
            :options="templates"
            option-label="name"
            option-value="id"
            :placeholder="t('activity.create.templatePlaceholder')"
            class="w-full"
            data-testid="activity-template-select"
          />
        </div>

        <!-- タイトル -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('activity.create.titleLabel') }} <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="title"
            class="w-full"
            maxlength="200"
            :placeholder="t('activity.create.titlePlaceholder')"
            data-testid="activity-title-input"
          />
        </div>

        <!-- 活動日 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('activity.create.dateLabel') }} <span class="text-red-500">*</span>
          </label>
          <DatePicker
            v-model="activityDate"
            class="w-full"
            date-format="yy/mm/dd"
            show-icon
            data-testid="activity-date-input"
          />
        </div>

        <template v-if="selectedTemplate">
          <!-- 公開範囲 -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('activity.create.visibilityLabel') }}</label>
            <Select
              v-model="visibility"
              :options="visibilityOptions"
              option-label="label"
              option-value="value"
              class="w-full"
              data-testid="activity-visibility-select"
            />
          </div>

          <!-- 説明（任意） -->
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('activity.create.descriptionLabel') }}</label>
            <Textarea
              v-model="description"
              class="w-full"
              rows="2"
              maxlength="10000"
              auto-resize
              data-testid="activity-description-input"
            />
          </div>

          <!-- カスタムフィールド（テンプレ定義） -->
          <div
            v-for="field in templateFields"
            :key="field.id"
            data-testid="activity-custom-field"
          >
            <label class="mb-1 block text-sm font-medium">
              {{ field.fieldLabel }}
              <span v-if="field.isRequired" class="text-red-500">*</span>
              <span v-if="field.unit" class="ml-1 text-xs text-surface-400">({{ field.unit }})</span>
            </label>

            <InputText
              v-if="field.fieldType === 'TEXT'"
              v-model="(fieldInputs[field.fieldKey] as string)"
              class="w-full"
              :placeholder="field.placeholder ?? ''"
            />
            <Textarea
              v-else-if="field.fieldType === 'TEXTAREA'"
              v-model="(fieldInputs[field.fieldKey] as string)"
              class="w-full"
              rows="2"
              auto-resize
              :placeholder="field.placeholder ?? ''"
            />
            <InputNumber
              v-else-if="field.fieldType === 'NUMBER'"
              v-model="(fieldInputs[field.fieldKey] as number)"
              class="w-full"
              :placeholder="field.placeholder ?? ''"
            />
            <DatePicker
              v-else-if="field.fieldType === 'DATE'"
              v-model="(fieldInputs[field.fieldKey] as Date)"
              class="w-full"
              date-format="yy/mm/dd"
              show-icon
            />
            <DatePicker
              v-else-if="field.fieldType === 'DATETIME'"
              v-model="(fieldInputs[field.fieldKey] as Date)"
              class="w-full"
              date-format="yy/mm/dd"
              show-time
              hour-format="24"
              show-icon
            />
            <Select
              v-else-if="field.fieldType === 'SELECT'"
              v-model="(fieldInputs[field.fieldKey] as string)"
              :options="parseSelectOptions(field.optionsJson)"
              option-label="label"
              option-value="value"
              class="w-full"
              :placeholder="field.placeholder ?? ''"
            />
            <label v-else-if="field.fieldType === 'CHECKBOX'" class="flex items-center gap-2 text-sm">
              <Checkbox v-model="(fieldInputs[field.fieldKey] as boolean)" binary />
              <span>{{ field.placeholder ?? field.fieldLabel }}</span>
            </label>
          </div>
        </template>
      </template>
    </div>

    <template v-if="!loadingTemplates && templates.length > 0" #footer>
      <Button
        :label="t('button.cancel')"
        text
        severity="secondary"
        :disabled="submitting"
        @click="visible = false"
      />
      <!-- 下書き保存: タイトル + 活動日のみで可 -->
      <Button
        :label="t('activity.create.saveDraft')"
        icon="pi pi-save"
        severity="secondary"
        outlined
        :loading="submitting"
        :disabled="!canSaveDraft"
        data-testid="activity-save-draft"
        @click="saveDraft"
      />
      <!-- 追加（全項目入力後・即PUBLISHED） -->
      <Button
        :label="t('activity.create.submit')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="activity-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
