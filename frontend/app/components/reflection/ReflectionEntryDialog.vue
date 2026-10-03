<script setup lang="ts">
/**
 * F06.5 振り返りエントリ作成/編集ダイアログ（§2.3 / §7 #7 upsert）。
 *
 * - 当日エントリ（非マスク）の作成・編集に使う。
 * - 楽観排他: 既存更新時は expectedVersion を送り、409 は呼び出し側でハンドリングできるよう例外を投げ直す。
 * - structured_content は ReflectionStructuredEditor（固定 5 階層）で編集する。
 * - テンプレート選択で初期見出しを組み立てる（§2.3.1）。
 * - ADHD ドライモード優先設計: 感情・内省系見出しはデフォルト OFF・ユーザーがオプトイン（AC-19）。
 */
import {
  type ReflectionStructuredContent,
  type ReflectionEntryResponse,
  type UpsertReflectionEntryRequest,
  emptyStructuredContent,
} from '~/types/reflection'
import type { JsonNode } from '~/composables/useReflectionStructuredContent'
import {
  REFLECTION_TEMPLATES,
  type ReflectionTemplateKey,
  buildInitialSections,
  getDefaultEnabledKeys,
} from '~/constants/reflectionTemplates'

const props = defineProps<{
  visible: boolean
  themeId: string
  targetDate: string
  /** 既存エントリ（編集時）。新規作成時は null。 */
  entry?: ReflectionEntryResponse | null
  /** テンプレート初期適用キー（新規作成時のみ・任意）。 */
  templateKey?: ReflectionTemplateKey | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: [entry: ReflectionEntryResponse]
}>()

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const { toStructured, toJsonNode } = useReflectionStructuredContent()

const content = ref<ReflectionStructuredContent>(emptyStructuredContent())
const saving = ref(false)
const selectedTemplate = ref<ReflectionTemplateKey>('NORMAL')

/**
 * 見出しキーごとの有効/無効フラグ（チェックボックス状態）。
 * テンプレート切り替えのたびに getDefaultEnabledKeys() でリセットされる。
 */
const sectionKeyEnabled = ref<Record<string, boolean>>({})

/**
 * applyTemplate が sectionKeyEnabled を書き換える際にウォッチャーの二重発火を防ぐフラグ。
 * applyTemplate は自前で content を生成するため、watch による再生成は不要。
 */
let _skipHeadingWatch = false

const templateOptions = computed(() =>
  REFLECTION_TEMPLATES.map(tpl => ({ label: t(tpl.labelKey), value: tpl.key })),
)

const isEdit = computed(() => !!props.entry?.id)

/** 現在選択中のテンプレート定義 */
const currentTemplate = computed(() =>
  REFLECTION_TEMPLATES.find(x => x.key === selectedTemplate.value) ?? null,
)

/**
 * 見出しチェックボックスを表示するか（sectionHeadingKeys が 2 件以上あるテンプレートのみ）。
 * 1 件以下は選択肢が無意味なので表示しない。VOCAB など sectionPresets 専用も非表示。
 */
const showHeadingSelector = computed(() => {
  const tpl = currentTemplate.value
  if (!tpl || tpl.sectionPresets) return false
  return tpl.sectionHeadingKeys.length >= 2
})

// ダイアログを開くたびに初期化する。
watch(
  () => props.visible,
  (open) => {
    if (!open) return
    if (props.entry?.structuredContent) {
      content.value = toStructured(props.entry.structuredContent as unknown as JsonNode)
    }
    else {
      selectedTemplate.value = props.templateKey ?? 'NORMAL'
      applyTemplate(selectedTemplate.value)
    }
  },
  { immediate: true },
)

/** テンプレートから初期 structured_content を組み立てる（§2.3.1）。 */
function applyTemplate(key: ReflectionTemplateKey) {
  const tpl = REFLECTION_TEMPLATES.find(x => x.key === key)
  if (!tpl) {
    _skipHeadingWatch = true
    sectionKeyEnabled.value = {}
    _skipHeadingWatch = false
    content.value = emptyStructuredContent()
    return
  }
  // チェックボックス状態をデフォルトにリセット（感情/内省系は OFF）
  // watch 二重発火防止: applyTemplate は直後に content を自分で組み立てる
  _skipHeadingWatch = true
  sectionKeyEnabled.value = getDefaultEnabledKeys(tpl)
  _skipHeadingWatch = false
  const enabledSet = new Set(
    Object.entries(sectionKeyEnabled.value)
      .filter(([, v]) => v)
      .map(([k]) => k),
  )
  const sections = buildInitialSections(tpl, t, enabledSet)
  content.value = {
    main_theme: '',
    sections,
    free_note: '',
  }
}

function onTemplateChange(key: ReflectionTemplateKey) {
  selectedTemplate.value = key
  applyTemplate(key)
}

/**
 * sectionKeyEnabled の変化を監視し、チェックボックス変更時に content を再生成する。
 * deep watch により Object 内のフラグ変更を検知する。
 * applyTemplate による書き換え時は _skipHeadingWatch フラグで二重発火をスキップする。
 */
watch(
  sectionKeyEnabled,
  () => {
    if (_skipHeadingWatch) return
    const tpl = currentTemplate.value
    if (!tpl) return
    const enabledSet = new Set(
      Object.entries(sectionKeyEnabled.value)
        .filter(([, v]) => v)
        .map(([k]) => k),
    )
    const sections = buildInitialSections(tpl, t, enabledSet)
    content.value = {
      main_theme: content.value.main_theme,
      sections,
      free_note: content.value.free_note,
    }
  },
  { deep: true },
)

function close() {
  emit('update:visible', false)
}

async function save() {
  saving.value = true
  try {
    const body: UpsertReflectionEntryRequest = {
      themeId: props.themeId,
      targetDate: props.targetDate,
      // 生成型 structuredContent は JsonNode(opaque) のため UI 構造型から cast して詰める。
      structuredContent: toJsonNode(content.value) as UpsertReflectionEntryRequest['structuredContent'],
      expectedVersion: props.entry?.version ?? undefined,
    }
    const res = await reflectionApi.upsertEntry(body)
    notification.success(t('reflection.entry.saved'))
    emit('saved', res.data)
    close()
  }
  catch (e: unknown) {
    const status = (e as { response?: { status?: number }; statusCode?: number })?.response?.status
      ?? (e as { statusCode?: number })?.statusCode
    if (status === 409) {
      notification.error(t('reflection.entry.conflict'))
    }
    else {
      notification.error(t('reflection.entry.save_failed'))
    }
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="isEdit ? t('reflection.entry.edit') : t('reflection.entry.create')"
    :style="{ width: '640px', maxWidth: '95vw' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-4">
      <!-- テンプレート選択（新規作成時のみ） -->
      <div v-if="!isEdit">
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.template.label') }}</label>
        <Select
          :model-value="selectedTemplate"
          :options="templateOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          @update:model-value="onTemplateChange"
        />
        <p class="mt-1 text-xs text-surface-500">{{ t('reflection.template.help') }}</p>

        <!-- 見出し選択チェックボックス（オプトイン: 感情/内省系はデフォルト OFF）-->
        <div v-if="showHeadingSelector && currentTemplate" class="mt-3 space-y-1">
          <p class="mb-1 text-sm font-medium">{{ t('reflection.template.heading_selector_label') }}</p>
          <div
            v-for="key in currentTemplate.sectionHeadingKeys"
            :key="key"
            class="flex items-center gap-2"
          >
            <Checkbox
              v-model="sectionKeyEnabled[key]"
              :input-id="`heading-check-${key}`"
              :binary="true"
            />
            <label :for="`heading-check-${key}`" class="cursor-pointer select-none text-sm">
              {{ t(key) }}
              <span
                v-if="currentTemplate.optionalSectionKeys?.includes(key)"
                class="ml-1 text-xs text-surface-400"
              >{{ t('reflection.template.heading_optional_badge') }}</span>
            </label>
          </div>
        </div>
      </div>

      <ReflectionStructuredEditor v-model="content" />
    </div>

    <template #footer>
      <Button :label="t('reflection.common.cancel')" severity="secondary" text @click="close" />
      <Button :label="t('reflection.entry.save')" :loading="saving" icon="pi pi-check" @click="save" />
    </template>
  </Dialog>
</template>
