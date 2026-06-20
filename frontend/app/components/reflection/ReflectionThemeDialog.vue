<script setup lang="ts">
/**
 * F06.5 テーマ作成/編集ダイアログ（§7 #2/#4）。
 *
 * - 新規作成は CreateReflectionThemeRequest、編集は UpdateReflectionThemeRequest（部分更新）。
 * - exam_date クリアは examDateCleared=true で明示（PATCH の null 曖昧さ回避）。
 * - visibility / recallIntervalDays は MVP 固定ゆえ送らない（BE が PRIVATE / 1,3,7,14 固定）。
 */
import {
  type ReflectionThemeResponse,
  type CreateReflectionThemeRequest,
  type UpdateReflectionThemeRequest,
  type ReflectionSourceType,
  REFLECTION_SOURCE_TYPES,
} from '~/types/reflection'

const props = defineProps<{
  visible: boolean
  /** 編集対象（null なら新規作成）。 */
  theme?: ReflectionThemeResponse | null
  /** 新規作成時のプリセット（今日ビューの空きコマからの作成）。 */
  presetTitle?: string | null
  presetSlotKind?: string | null
  presetSlotId?: number | null
  presetSourceType?: ReflectionSourceType | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: [theme: ReflectionThemeResponse]
}>()

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()

const saving = ref(false)
const form = ref({
  title: '',
  description: '',
  sourceType: 'FREE' as ReflectionSourceType,
  // PrimeVue DatePicker は Date を扱う。応答の ISO 文字列とは toIsoDate / parseIsoDate で相互変換する。
  examDate: null as Date | null,
})

const isEdit = computed(() => !!props.theme?.id)

const sourceTypeOptions = computed(() =>
  REFLECTION_SOURCE_TYPES.map(s => ({ label: t(`reflection.source_type.${s}`), value: s })),
)

// 過去日の考査はリマインド生成されない（§5.5）。注意文を出す。
const examDateIsPast = computed(() => {
  if (!form.value.examDate) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return new Date(form.value.examDate) < today
})

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    if (props.theme) {
      form.value = {
        title: props.theme.title ?? '',
        description: props.theme.description ?? '',
        sourceType: (props.theme.sourceType as ReflectionSourceType) ?? 'FREE',
        examDate: props.theme.examDate ? new Date(props.theme.examDate) : null,
      }
    }
    else {
      form.value = {
        title: props.presetTitle ?? '',
        description: '',
        sourceType: props.presetSourceType ?? 'FREE',
        examDate: null,
      }
    }
  },
  { immediate: true },
)

function close() {
  emit('update:visible', false)
}

function toIsoDate(d: Date | null): string | null {
  if (!d) return null
  const dt = d instanceof Date ? d : new Date(d)
  if (Number.isNaN(dt.getTime())) return null
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}

async function save() {
  if (!form.value.title.trim()) return
  saving.value = true
  try {
    let saved: ReflectionThemeResponse
    if (isEdit.value && props.theme?.id) {
      const body: UpdateReflectionThemeRequest = {
        title: form.value.title,
        description: form.value.description || undefined,
        sourceType: form.value.sourceType,
        examDate: toIsoDate(form.value.examDate) ?? undefined,
        examDateCleared: !form.value.examDate && !!props.theme.examDate,
      }
      const res = await reflectionApi.updateTheme(props.theme.id, body)
      saved = res.data
    }
    else {
      const body: CreateReflectionThemeRequest = {
        title: form.value.title,
        description: form.value.description || undefined,
        sourceType: form.value.sourceType,
        linkedSlotKind: (props.presetSlotKind as CreateReflectionThemeRequest['linkedSlotKind']) ?? undefined,
        linkedSlotId: props.presetSlotId ?? undefined,
        examDate: toIsoDate(form.value.examDate) ?? undefined,
      }
      const res = await reflectionApi.createTheme(body)
      saved = res.data
    }
    notification.success(t('reflection.entry.saved'))
    emit('saved', saved)
    close()
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
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
    :header="isEdit ? t('reflection.theme.edit') : t('reflection.theme.create')"
    :style="{ width: '520px', maxWidth: '95vw' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.theme.title_label') }}</label>
        <InputText
          v-model="form.title"
          :placeholder="t('reflection.theme.title_placeholder')"
          class="w-full"
          maxlength="120"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.theme.source_type_label') }}</label>
        <Select
          v-model="form.sourceType"
          :options="sourceTypeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.theme.description_label') }}</label>
        <Textarea
          v-model="form.description"
          :placeholder="t('reflection.theme.description_placeholder')"
          class="w-full"
          rows="2"
          auto-resize
          maxlength="500"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.theme.exam_date_label') }}</label>
        <DatePicker
          v-model="form.examDate"
          date-format="yy-mm-dd"
          show-icon
          show-button-bar
          class="w-full"
        />
        <p class="mt-1 text-xs text-surface-500">{{ t('reflection.theme.exam_date_help') }}</p>
        <p v-if="examDateIsPast" class="mt-1 text-xs text-amber-600">
          {{ t('reflection.theme.exam_date_past_warning') }}
        </p>
      </div>

      <!-- 想起間隔は MVP 固定表示 -->
      <p class="text-xs text-surface-400">{{ t('reflection.theme.recall_interval_fixed', { days: '1・3・7・14' }) }}</p>
    </div>

    <template #footer>
      <Button :label="t('reflection.common.cancel')" severity="secondary" text @click="close" />
      <Button :label="t('reflection.entry.save')" :loading="saving" :disabled="!form.title.trim()" icon="pi pi-check" @click="save" />
    </template>
  </Dialog>
</template>
