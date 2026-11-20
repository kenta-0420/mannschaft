<script setup lang="ts">
import type { EffortFieldSetting, EffortTemplateSettings } from '~/composables/useEffortTemplateSettingsApi'
import { extractEffortFieldErrors } from '~/utils/effortFields'
import { classifyLoadError, getLoadErrorHttpStatus } from '~/utils/loadError'

type Semantic = EffortFieldSetting['semantic']
type Aggregation = EffortFieldSetting['aggregation']
type FieldType = 'NUMBER' | 'TEXT' | 'SELECT' | 'DATE' | 'DATETIME' | 'CHECKBOX'

interface Row {
  fieldKey: string
  label: string
  type: FieldType
  semantic: Semantic
  unit: string
  aggregation: Aggregation
}

/**
 * F06.4.1 チームテンプレート編集画面の「頑張り記録の指標」セクション（API 契約 §7）。
 * 既存の activity テンプレート保存とは別の API・別の version で保存する。
 * 許容組合せの最終判定は BE（400 の fieldErrors を該当項目に表示する）。ここは選択肢を型で絞るだけ。
 */
const props = defineProps<{
  /** 数値の team ID（activity テンプレートの scopeId）。 */
  teamId: number
  templateId: number
}>()

const { t } = useI18n()
const { get, update } = useEffortTemplateSettingsApi()

const NONE = ''
const loading = ref(true)
/** 取得が 403（管理権限なし）のときはセクションごと出さない。 */
const forbidden = ref(false)
const loadFailed = ref(false)
const saving = ref(false)
const conflict = ref(false)
const saved = ref(false)
const saveFailed = ref(false)
const version = ref(0)
const rows = ref<Row[]>([])
const durationKey = ref<string>(NONE)
const subjectKey = ref<string>(NONE)
const fieldErrors = ref<Record<string, string>>({})
let loadSeq = 0

/** 型ごとに選べる semantic（親設計 §3・BE の許容組合せと同じ範囲）。 */
const SEMANTICS_BY_TYPE: Record<FieldType, Semantic[]> = {
  NUMBER: ['NUMBER', 'DURATION', 'WEIGHT', 'INTENSITY'],
  TEXT: ['TEXT', 'SUBJECT', 'MENU'],
  SELECT: ['TEXT', 'SUBJECT'],
  DATE: ['DATE'],
  DATETIME: ['DATETIME'],
  CHECKBOX: ['CHECKBOX'],
}

/** semantic を切り替えたときの固定の単位・集計方法。NUMBER だけは管理者が選ぶ。 */
function defaultsFor(semantic: Semantic): { unit: string; aggregation: Aggregation } {
  switch (semantic) {
    case 'DURATION': return { unit: 'MINUTE', aggregation: 'SUM' }
    case 'WEIGHT': return { unit: 'KILOGRAM', aggregation: 'LATEST' }
    case 'INTENSITY': return { unit: 'RPE', aggregation: 'NONE' }
    default: return { unit: 'NONE', aggregation: 'NONE' }
  }
}

function semanticOptions(row: Row) {
  return SEMANTICS_BY_TYPE[row.type].map((s) => ({ value: s, label: t(`effort.templateSettings.semantic.${s}`) }))
}

const aggregationOptions = computed(() =>
  (['NONE', 'SUM'] as Aggregation[]).map((a) => ({ value: a, label: t(`effort.templateSettings.aggregation.${a}`) })),
)

function aggregationLabel(a: Aggregation) {
  return t(`effort.templateSettings.aggregation.${a}`)
}

function isNumberSemantic(row: Row) {
  return row.semantic === 'NUMBER'
}

function candidates(semantic: Semantic) {
  return [
    { value: NONE, label: t('effort.templateSettings.none') },
    ...rows.value.filter((r) => r.semantic === semantic).map((r) => ({ value: r.fieldKey, label: r.label })),
  ]
}
const durationOptions = computed(() => candidates('DURATION'))
const subjectOptions = computed(() => candidates('SUBJECT'))

function apply(settings: EffortTemplateSettings) {
  const def = settings.definition
  version.value = settings.version ?? 0
  rows.value = (def?.fields ?? []).map((f) => ({
    fieldKey: f.fieldKey ?? '',
    label: f.label ?? f.fieldKey ?? '',
    type: (f.type ?? 'TEXT') as FieldType,
    semantic: (f.semantic ?? 'TEXT') as Semantic,
    unit: f.unit ?? 'NONE',
    aggregation: (f.aggregation ?? 'NONE') as Aggregation,
  }))
  durationKey.value = def?.durationFieldKey ?? NONE
  subjectKey.value = def?.subjectFieldKey ?? NONE
}

async function load() {
  const seq = ++loadSeq
  loading.value = true
  loadFailed.value = false
  fieldErrors.value = {}
  try {
    const res = await get(props.teamId, props.templateId)
    if (seq !== loadSeq) return
    apply(res.data)
  } catch (e) {
    if (seq !== loadSeq) return
    if (classifyLoadError(e) === 'forbidden') forbidden.value = true
    else loadFailed.value = true
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function reloadAfterConflict() {
  conflict.value = false
  saved.value = false
  await load()
}

function onSemanticChange(row: Row) {
  const d = defaultsFor(row.semantic)
  row.unit = d.unit
  row.aggregation = d.aggregation
  // 対応先に選んでいた項目が別の意味になったら対応を外す。該当 semantic が 1 項目だけなら自動で対応づける。
  const dur = rows.value.filter((r) => r.semantic === 'DURATION')
  const sub = rows.value.filter((r) => r.semantic === 'SUBJECT')
  if (!dur.some((r) => r.fieldKey === durationKey.value)) durationKey.value = dur.length === 1 ? dur[0]!.fieldKey : NONE
  if (!sub.some((r) => r.fieldKey === subjectKey.value)) subjectKey.value = sub.length === 1 ? sub[0]!.fieldKey : NONE
}

function errorOf(path: string): string | undefined {
  return fieldErrors.value[path]
}

/** 項目・対応先のどれにも紐づかないエラー（"fields" 全体など）。 */
const generalErrors = computed(() => {
  const known = new Set([
    ...rows.value.map((r) => `fields.${r.fieldKey}`),
    'fields.durationFieldKey',
    'fields.subjectFieldKey',
  ])
  return Object.entries(fieldErrors.value).filter(([k]) => !known.has(k)).map(([, v]) => v)
})

async function save() {
  saving.value = true
  saved.value = false
  saveFailed.value = false
  conflict.value = false
  fieldErrors.value = {}
  try {
    const res = await update(props.teamId, props.templateId, {
      version: version.value,
      fields: rows.value.map((r) => ({
        fieldKey: r.fieldKey,
        semantic: r.semantic,
        unit: r.unit,
        aggregation: r.aggregation,
      })),
      durationFieldKey: durationKey.value === NONE ? undefined : durationKey.value,
      subjectFieldKey: subjectKey.value === NONE ? undefined : subjectKey.value,
    })
    apply(res.data)
    saved.value = true
  } catch (e) {
    const status = getLoadErrorHttpStatus(e)
    if (status === 409) {
      conflict.value = true
    } else if (status === 400) {
      const errs = extractEffortFieldErrors(e)
      if (errs.length > 0) {
        fieldErrors.value = Object.fromEntries(errs.map((fe) => [fe.field, fe.message]))
      } else {
        saveFailed.value = true
      }
    } else if (classifyLoadError(e) === 'forbidden') {
      forbidden.value = true
    } else {
      saveFailed.value = true
    }
  } finally {
    saving.value = false
  }
}

watch(() => [props.teamId, props.templateId], load)
onMounted(load)
</script>

<template>
  <SectionCard v-if="!forbidden" data-testid="effort-template-settings" :title="t('effort.templateSettings.title')">
    <p class="mb-3 text-sm text-surface-500">{{ t('effort.templateSettings.description') }}</p>

    <PageLoading v-if="loading" size="32px" />

    <div v-else-if="loadFailed" class="flex flex-col items-start gap-2" role="alert" data-testid="effort-settings-load-error">
      <p class="text-sm text-red-600 dark:text-red-400">{{ t('effort.templateSettings.loadError') }}</p>
      <Button :label="t('effort.templateSettings.retry')" icon="pi pi-refresh" outlined class="min-h-11" @click="load" />
    </div>

    <div v-else-if="rows.length === 0" class="text-sm text-surface-500">{{ t('effort.templateSettings.noFields') }}</div>

    <div v-else class="flex flex-col gap-4">
      <div
        v-for="row in rows"
        :key="row.fieldKey"
        class="flex flex-col gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        :data-testid="`effort-setting-row-${row.fieldKey}`"
      >
        <div class="break-words text-sm font-medium">{{ row.label }}</div>
        <div class="grid grid-cols-1 gap-2 sm:grid-cols-3">
          <label class="flex min-w-0 flex-col gap-1 text-xs text-surface-500">
            {{ t('effort.templateSettings.semanticLabel') }}
            <Select
              v-model="row.semantic"
              :options="semanticOptions(row)"
              option-label="label"
              option-value="value"
              class="min-h-11 w-full text-base"
              :aria-label="`${row.label} ${t('effort.templateSettings.semanticLabel')}`"
              :data-testid="`effort-setting-semantic-${row.fieldKey}`"
              @change="onSemanticChange(row)"
            />
          </label>
          <label class="flex min-w-0 flex-col gap-1 text-xs text-surface-500">
            {{ t('effort.templateSettings.unitLabel') }}
            <InputText
              v-model="row.unit"
              :disabled="!isNumberSemantic(row)"
              maxlength="32"
              class="min-h-11 w-full text-base"
              :aria-label="`${row.label} ${t('effort.templateSettings.unitLabel')}`"
              :data-testid="`effort-setting-unit-${row.fieldKey}`"
            />
          </label>
          <label class="flex min-w-0 flex-col gap-1 text-xs text-surface-500">
            {{ t('effort.templateSettings.aggregationLabel') }}
            <Select
              v-if="isNumberSemantic(row)"
              v-model="row.aggregation"
              :options="aggregationOptions"
              option-label="label"
              option-value="value"
              class="min-h-11 w-full text-base"
              :aria-label="`${row.label} ${t('effort.templateSettings.aggregationLabel')}`"
              :data-testid="`effort-setting-aggregation-${row.fieldKey}`"
            />
            <span v-else class="flex min-h-11 items-center text-base text-surface-700 dark:text-surface-200">
              {{ aggregationLabel(row.aggregation) }}
            </span>
          </label>
        </div>
        <p
          v-if="errorOf(`fields.${row.fieldKey}`)"
          class="text-sm text-red-600 dark:text-red-400"
          role="alert"
          :data-testid="`effort-setting-error-${row.fieldKey}`"
        >{{ errorOf(`fields.${row.fieldKey}`) }}</p>
      </div>

      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label class="flex min-w-0 flex-col gap-1 text-sm">
          {{ t('effort.templateSettings.durationField') }}
          <Select
            v-model="durationKey"
            :options="durationOptions"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full text-base"
            data-testid="effort-setting-duration"
          />
          <span v-if="errorOf('fields.durationFieldKey')" class="text-sm text-red-600 dark:text-red-400" role="alert" data-testid="effort-setting-duration-error">
            {{ errorOf('fields.durationFieldKey') }}
          </span>
        </label>
        <label class="flex min-w-0 flex-col gap-1 text-sm">
          {{ t('effort.templateSettings.subjectField') }}
          <Select
            v-model="subjectKey"
            :options="subjectOptions"
            option-label="label"
            option-value="value"
            class="min-h-11 w-full text-base"
            data-testid="effort-setting-subject"
          />
          <span v-if="errorOf('fields.subjectFieldKey')" class="text-sm text-red-600 dark:text-red-400" role="alert" data-testid="effort-setting-subject-error">
            {{ errorOf('fields.subjectFieldKey') }}
          </span>
        </label>
      </div>

      <p v-for="msg in generalErrors" :key="msg" class="text-sm text-red-600 dark:text-red-400" role="alert" data-testid="effort-setting-general-error">{{ msg }}</p>

      <div v-if="conflict" class="flex flex-col items-start gap-2" role="alert" data-testid="effort-settings-conflict">
        <p class="text-sm text-red-600 dark:text-red-400">{{ t('effort.templateSettings.conflict') }}</p>
        <Button :label="t('effort.templateSettings.reload')" icon="pi pi-refresh" outlined class="min-h-11" @click="reloadAfterConflict" />
      </div>
      <p v-if="saveFailed" class="text-sm text-red-600 dark:text-red-400" role="alert" data-testid="effort-settings-save-error">{{ t('effort.templateSettings.saveError') }}</p>
      <p v-if="saved" class="text-sm text-green-700 dark:text-green-400" role="status" data-testid="effort-settings-saved">{{ t('effort.templateSettings.saved') }}</p>

      <div>
        <Button
          :label="t('effort.templateSettings.save')"
          icon="pi pi-check"
          :loading="saving"
          class="min-h-11"
          data-testid="effort-settings-save"
          @click="save"
        />
      </div>
    </div>
  </SectionCard>
</template>
