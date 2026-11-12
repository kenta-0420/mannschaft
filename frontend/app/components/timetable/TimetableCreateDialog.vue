<script setup lang="ts">
/**
 * 時間割 — 作成ダイアログ
 *
 * フォーム入力を子側でローカル保持し、確定時は payload を emit で親へ渡す。
 * API 呼び出し・term 一覧の取得・notification は親 (teams/[id]/timetable.vue) が担当。
 * visible が true になった瞬間にフォームを初期化する（元コードの open*Dialog 相当）。
 */
import type { TimetableTerm, TimetableVisibility } from '~/types/timetable'

interface CreatePayload {
  name: string
  termId: number
  effectiveFrom: string
  effectiveUntil: string | null
  visibility: TimetableVisibility
  weekPatternEnabled: boolean
  weekPatternBaseDate: string | null
  notes: string | null
}

const props = defineProps<{
  visible: boolean
  terms: TimetableTerm[]
  submitting: boolean
  /** 作成 API が返した項目別のエラー（400 の fieldErrors）。項目名 → メッセージ。 */
  fieldErrors?: Record<string, string>
  /** 項目に紐づかない作成エラー（TIMETABLE_040 など）。 */
  formError?: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', payload: CreatePayload): void
}>()

const { t } = useI18n()

const form = ref({
  name: '',
  termId: null as number | null,
  effectiveFrom: '',
  effectiveUntil: '',
  visibility: 'MEMBERS_ONLY' as TimetableVisibility,
  weekPatternEnabled: false,
  weekPatternBaseDate: '',
  notes: '',
})

/** 送信前に検出した項目別のエラー。API が返したものは props.fieldErrors 側。 */
const clientErrors = ref<Record<string, string>>({})

function errorOf(field: string): string | undefined {
  return clientErrors.value[field] ?? props.fieldErrors?.[field]
}

function resetForm() {
  clientErrors.value = {}
  form.value = {
    name: '',
    termId: null,
    effectiveFrom: '',
    effectiveUntil: '',
    visibility: 'MEMBERS_ONLY',
    weekPatternEnabled: false,
    weekPatternBaseDate: '',
    notes: '',
  }
}

watch(
  () => props.visible,
  (v, prev) => {
    if (v && !prev) resetForm()
  },
)

const visibleModel = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

const visibilityOptions = computed(() => [
  { label: t('timetable.visibility_members_only'), value: 'MEMBERS_ONLY' as TimetableVisibility },
  { label: t('timetable.visibility_public'), value: 'PUBLIC' as TimetableVisibility },
])

function submit() {
  if (!form.value.name || !form.value.termId || !form.value.effectiveFrom) return
  // 適用開始日より前の終了日は API に流す前に弾く（BE は TIMETABLE_040/400 で拒否する）
  const errors: Record<string, string> = {}
  if (form.value.effectiveUntil && form.value.effectiveUntil < form.value.effectiveFrom) {
    errors.effectiveUntil = t('timetable.effective_until_before_from')
  }
  clientErrors.value = errors
  if (Object.keys(errors).length > 0) return
  emit('submit', {
    name: form.value.name,
    termId: form.value.termId,
    effectiveFrom: form.value.effectiveFrom,
    effectiveUntil: form.value.effectiveUntil || null,
    visibility: form.value.visibility,
    weekPatternEnabled: form.value.weekPatternEnabled,
    weekPatternBaseDate: form.value.weekPatternBaseDate || null,
    notes: form.value.notes || null,
  })
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    :header="$t('timetable.create_timetable')"
    :modal="true"
    class="w-full max-w-lg"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.timetable_name') }}</label>
        <InputText
          v-model="form.name"
          class="w-full"
          :placeholder="$t('timetable.timetable_name')"
          :invalid="!!errorOf('name')"
        />
        <small v-if="errorOf('name')" class="mt-1 block text-red-600" role="alert" data-testid="timetable-create-error-name">{{ errorOf('name') }}</small>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.term') }}</label>
        <Select
          v-model="form.termId"
          :options="terms"
          option-label="name"
          option-value="id"
          class="w-full"
          :placeholder="$t('timetable.term')"
          :invalid="!!errorOf('termId')"
        />
        <small v-if="errorOf('termId')" class="mt-1 block text-red-600" role="alert" data-testid="timetable-create-error-termId">{{ errorOf('termId') }}</small>
        <small
          v-if="terms.length === 0"
          class="mt-1 block text-surface-500"
          data-testid="timetable-create-no-term"
        >{{ $t('timetable.no_term_for_create') }}</small>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.effective_from')
          }}</label>
          <InputText v-model="form.effectiveFrom" type="date" class="w-full" :invalid="!!errorOf('effectiveFrom')" />
          <small v-if="errorOf('effectiveFrom')" class="mt-1 block text-red-600" role="alert" data-testid="timetable-create-error-effectiveFrom">{{ errorOf('effectiveFrom') }}</small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.effective_until')
          }}</label>
          <InputText v-model="form.effectiveUntil" type="date" class="w-full" :invalid="!!errorOf('effectiveUntil')" />
          <small v-if="errorOf('effectiveUntil')" class="mt-1 block text-red-600" role="alert" data-testid="timetable-create-error-effectiveUntil">{{ errorOf('effectiveUntil') }}</small>
        </div>
      </div>
      <small v-if="formError" class="block text-red-600" role="alert" data-testid="timetable-create-form-error">{{ formError }}</small>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.visibility_label') }}</label>
        <Select
          v-model="form.visibility"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.notes') }}</label>
        <Textarea v-model="form.notes" class="w-full" rows="2" />
      </div>
    </div>
    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        @click="visibleModel = false"
      />
      <Button
        :label="$t('button.create')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!form.name || !form.termId || !form.effectiveFrom"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
