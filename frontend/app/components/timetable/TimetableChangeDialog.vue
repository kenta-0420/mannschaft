<script setup lang="ts">
/**
 * 時間割 — 臨時変更登録ダイアログ
 *
 * フォーム入力を子側でローカル保持し、確定時は payload を emit で親へ渡す。
 * API 呼び出し・notification は親 (teams/[id]/timetable.vue) が担当。
 * visible が true になった瞬間にフォームを初期化する（元コードの open*Dialog 相当）。
 */
import type { ChangeType } from '~/types/timetable'

interface ChangePayload {
  targetDate: string
  periodNumber: number | null
  changeType: ChangeType
  subjectName: string | null
  teacherName: string | null
  roomName: string | null
  reason: string | null
  notifyMembers: boolean
}

const props = defineProps<{
  visible: boolean
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submit', payload: ChangePayload): void
}>()

const { t } = useI18n()

const form = ref({
  targetDate: '',
  periodNumber: null as number | null,
  changeType: 'REPLACE' as ChangeType,
  subjectName: '',
  teacherName: '',
  roomName: '',
  reason: '',
  notifyMembers: true,
})

function resetForm() {
  form.value = {
    targetDate: '',
    periodNumber: null,
    changeType: 'REPLACE',
    subjectName: '',
    teacherName: '',
    roomName: '',
    reason: '',
    notifyMembers: true,
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

const changeTypeOptions = computed(() => [
  { label: t('timetable.change_type_replace'), value: 'REPLACE' as ChangeType },
  { label: t('timetable.change_type_cancel'), value: 'CANCEL' as ChangeType },
  { label: t('timetable.change_type_add'), value: 'ADD' as ChangeType },
  { label: t('timetable.change_type_day_off'), value: 'DAY_OFF' as ChangeType },
])

function submit() {
  if (!form.value.targetDate || !form.value.changeType) return
  emit('submit', {
    targetDate: form.value.targetDate,
    periodNumber: form.value.periodNumber,
    changeType: form.value.changeType,
    subjectName: form.value.subjectName || null,
    teacherName: form.value.teacherName || null,
    roomName: form.value.roomName || null,
    reason: form.value.reason || null,
    notifyMembers: form.value.notifyMembers,
  })
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    :header="$t('timetable.add_change')"
    :modal="true"
    class="w-full max-w-lg"
  >
    <div class="space-y-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_date') }}</label>
          <InputText v-model="form.targetDate" type="date" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.period_number')
          }}</label>
          <InputNumber
            v-model="form.periodNumber"
            class="w-full"
            :min="1"
            :max="15"
            :disabled="form.changeType === 'DAY_OFF'"
            :placeholder="form.changeType === 'DAY_OFF' ? $t('timetable.period_all') : $t('timetable.period_number_placeholder')"
          />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_type') }}</label>
        <Select
          v-model="form.changeType"
          :options="changeTypeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div
        v-if="form.changeType === 'REPLACE' || form.changeType === 'ADD'"
        class="space-y-3"
      >
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.change_subject')
          }}</label>
          <InputText
            v-model="form.subjectName"
            class="w-full"
            :placeholder="$t('timetable.change_subject')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{
            $t('timetable.change_teacher')
          }}</label>
          <InputText v-model="form.teacherName" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_room') }}</label>
          <InputText v-model="form.roomName" class="w-full" />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('timetable.change_reason') }}</label>
        <InputText v-model="form.reason" class="w-full" />
      </div>
      <div class="flex items-center gap-2">
        <Checkbox v-model="form.notifyMembers" binary input-id="notify" />
        <label for="notify" class="text-sm">{{ $t('timetable.notify_members') }}</label>
      </div>
    </div>
    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        @click="visibleModel = false"
      />
      <Button
        :label="$t('button.save')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!form.targetDate || !form.changeType"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
