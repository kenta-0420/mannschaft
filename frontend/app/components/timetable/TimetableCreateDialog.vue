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

function resetForm() {
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
        />
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
        />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.effective_from')
          }}</label>
          <InputText v-model="form.effectiveFrom" type="date" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.effective_until')
          }}</label>
          <InputText v-model="form.effectiveUntil" type="date" class="w-full" />
        </div>
      </div>
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
