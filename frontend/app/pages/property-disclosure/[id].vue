<script setup lang="ts">
/**
 * 重要事項説明書（参考） ドラフト編集ページ（F09.14 Phase 2-β-5）。
 *
 * URL: /property-disclosure/[id]?organizationId=N
 *
 * - テンプレートの form_schema を取得し、DisclosureFormFieldRenderer で動的レンダリング
 * - 自動引用バナー（個人情報許諾 + 自動引用更新）
 * - PDF/Excel 出力ボタン
 * - 保存（PUT、楽観的ロック）
 * - 409 → 最新版を再取得して衝突解決
 * - status=EXPORTED は編集禁止表示（コピー機能は Phase 3）
 */
import type {
  DisclosureFormDraft,
  DisclosureFormTemplate,
} from '~/types/disclosure'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const { success: showSuccess, error: showError } = useNotification()

const organizationId = computed<string>(() => {
  const raw = route.query.organizationId
  return raw ? String(Array.isArray(raw) ? raw[0] : raw) : ''
})
const draftId = computed<number>(() => Number(route.params.id))

const api = computed(() => useDisclosureApi(organizationId.value))

const draft = ref<DisclosureFormDraft | null>(null)
const template = ref<DisclosureFormTemplate | null>(null)
const loading = ref(false)
const saving = ref(false)

/** ドラフト + テンプレートを並行取得。 */
async function load() {
  if (!organizationId.value || !Number.isFinite(draftId.value)) return
  loading.value = true
  try {
    const fetched = await api.value.getDraft(draftId.value)
    draft.value = fetched
    template.value = await api.value.getTemplate(fetched.templateId)
  } catch {
    showError(t('disclosure.errors.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

const isLocked = computed(() => draft.value?.status === 'EXPORTED')

/** form_schema フィールドの値を双方向バインド。 */
function getFieldValue(fieldId: string): unknown {
  if (!draft.value) return undefined
  return draft.value.formData?.[fieldId]
}

function setFieldValue(fieldId: string, value: unknown) {
  if (!draft.value) return
  // formData を不変更新（Vue の reactivity を確実に発火）
  draft.value = {
    ...draft.value,
    formData: { ...draft.value.formData, [fieldId]: value },
  }
}

async function save() {
  if (!draft.value || isLocked.value) return
  saving.value = true
  try {
    const updated = await api.value.updateDraft(draftId.value, {
      templateId: draft.value.templateId,
      title: draft.value.title,
      targetDwellingUnitId: draft.value.targetDwellingUnitId,
      formData: draft.value.formData,
      version: draft.value.version,
    })
    draft.value = updated
    showSuccess(t('disclosure.saved'))
  } catch (err) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      showError(t('disclosure.errors.versionConflict'))
      // 最新版を再取得
      await load()
    } else {
      showError(t('disclosure.errors.saveFailed'))
    }
  } finally {
    saving.value = false
  }
}

function back() {
  navigateTo({
    path: '/property-disclosure',
    query: { organizationId: String(organizationId.value) },
  })
}

function onAutoFillRefreshed(refreshed: DisclosureFormDraft) {
  draft.value = refreshed
}
</script>

<template>
  <div class="space-y-4 p-4 md:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <Button
        icon="pi pi-arrow-left"
        :label="t('disclosure.back')"
        severity="secondary"
        text
        @click="back"
      />
      <div v-if="draft" class="flex flex-wrap items-center gap-2">
        <DisclosureExportButton
          :organization-id="Number(organizationId)"
          :draft-id="draft.id"
          :disabled="saving"
          @exported="load"
        />
        <Button
          icon="pi pi-save"
          :label="t('disclosure.actions.save')"
          severity="primary"
          :loading="saving"
          :disabled="isLocked"
          data-testid="disclosure-save-btn"
          @click="save"
        />
      </div>
    </header>

    <div
      v-if="loading"
      class="rounded-md border border-surface-200 p-8 text-center text-sm text-surface-500 dark:border-surface-700"
    >
      {{ t('disclosure.loading') }}
    </div>

    <article v-else-if="draft && template" class="space-y-4">
      <!-- ヘッダーカード（タイトル / 様式 / ステータス） -->
      <Card>
        <template #title>
          <div class="flex flex-wrap items-center gap-2">
            <Tag :value="t(`disclosure.draftStatus.${draft.status}`)" />
            <span class="text-sm text-surface-500">
              {{ template.name }} · v{{ draft.templateVersionSnapshot }}
            </span>
          </div>
        </template>
        <template #content>
          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ t('disclosure.fields.title') }}
            </label>
            <InputText
              v-model="draft.title"
              class="w-full"
              :disabled="isLocked"
              data-testid="disclosure-edit-title"
            />
          </div>
        </template>
      </Card>

      <!-- 編集禁止表示 -->
      <div
        v-if="isLocked"
        class="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-700 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-200"
        data-testid="disclosure-locked-banner"
      >
        <strong class="block">{{ t('disclosure.exported.lockedTitle') }}</strong>
        <p>{{ t('disclosure.exported.lockedMessage') }}</p>
      </div>

      <!-- 自動引用バナー -->
      <DisclosureAutoFillBanner
        v-if="!isLocked"
        :organization-id="Number(organizationId)"
        :draft-id="draft.id"
        :disabled="saving"
        @refreshed="onAutoFillRefreshed"
      />

      <!-- セクション別の動的フォーム -->
      <Card
        v-for="section in template.formSchema.sections"
        :key="section.id"
        :data-testid="`disclosure-section-${section.id}`"
      >
        <template #title>{{ section.title }}</template>
        <template #content>
          <div class="space-y-4">
            <DisclosureFormFieldRenderer
              v-for="field in section.fields"
              :key="field.id"
              :field="field"
              :model-value="getFieldValue(field.id)"
              :disabled="isLocked"
              @update:model-value="(v) => setFieldValue(field.id, v)"
            />
          </div>
        </template>
      </Card>
    </article>

    <div
      v-else
      class="rounded-md border border-dashed p-8 text-center text-sm text-surface-500"
    >
      {{ t('disclosure.errors.loadFailed') }}
    </div>
  </div>
</template>
