<script setup lang="ts">
// 活動記録 作成ダイアログ（「記録を追加」フロー）
// - テンプレ必須: テンプレ未作成なら入力フォームを出さず作成導線を案内する
// - 選択テンプレの fields[] を fieldType 別に動的描画（値は fieldKey をキーに保持）
// - 送信: scope_type/scope_id はクエリ・body は CreateActivityRequest（useActivityApi.createActivity）
import type {
  ActivityTemplate,
  ActivityTemplateField,
  CreateActivityRequestBody,
} from '~/types/activity'
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
const { getTemplates, createActivity } = useActivityApi()
const { resolveScopeId } = useActivityScopeId()
const { success: showSuccess, error: showError } = useNotification()

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
  if (val) {
    resetForm()
    loadTemplates()
  }
})

// === 送信 ===
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
        <!-- テンプレ選択 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('activity.create.templateLabel') }} <span class="text-red-500">*</span>
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

        <template v-if="selectedTemplate">
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

    <template v-if="templates.length > 0 && !loadingTemplates" #footer>
      <Button
        :label="t('button.cancel')"
        text
        severity="secondary"
        :disabled="submitting"
        @click="visible = false"
      />
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
