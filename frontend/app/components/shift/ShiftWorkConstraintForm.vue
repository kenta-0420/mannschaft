<script setup lang="ts">
import type { MemberWorkConstraintResponse, MemberWorkConstraintRequest } from '~/types/shift'

const props = defineProps<{
  constraint: MemberWorkConstraintResponse | null
  teamId: number
  /** null の場合はチームデフォルト編集 */
  userId: number | null
  loading?: boolean
}>()

const emit = defineEmits<{
  saved: [constraint: MemberWorkConstraintResponse]
  deleted: []
}>()

const { t } = useI18n()
const { upsertConstraint, upsertTeamDefault, deleteConstraint, deleteTeamDefault } =
  useMemberWorkConstraintApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

const saving = ref(false)
const deleting = ref(false)

interface FormState {
  maxMonthlyHours: number | null
  maxMonthlyDays: number | null
  maxConsecutiveDays: number | null
  maxNightShiftsPerMonth: number | null
  minRestHoursBetweenShifts: number | null
  note: string
}

const form = ref<FormState>({
  maxMonthlyHours: null,
  maxMonthlyDays: null,
  maxConsecutiveDays: null,
  maxNightShiftsPerMonth: null,
  minRestHoursBetweenShifts: null,
  note: '',
})

watch(
  () => props.constraint,
  (c) => {
    if (c) {
      form.value = {
        maxMonthlyHours: c.maxMonthlyHours ? parseFloat(c.maxMonthlyHours) : null,
        maxMonthlyDays: c.maxMonthlyDays,
        maxConsecutiveDays: c.maxConsecutiveDays,
        maxNightShiftsPerMonth: c.maxNightShiftsPerMonth,
        minRestHoursBetweenShifts: c.minRestHoursBetweenShifts
          ? parseFloat(c.minRestHoursBetweenShifts)
          : null,
        note: c.note ?? '',
      }
    }
  },
  { immediate: true },
)

async function save() {
  saving.value = true
  try {
    const payload: MemberWorkConstraintRequest = {
      maxMonthlyHours: form.value.maxMonthlyHours ?? undefined,
      maxMonthlyDays: form.value.maxMonthlyDays ?? undefined,
      maxConsecutiveDays: form.value.maxConsecutiveDays ?? undefined,
      maxNightShiftsPerMonth: form.value.maxNightShiftsPerMonth ?? undefined,
      minRestHoursBetweenShifts: form.value.minRestHoursBetweenShifts ?? undefined,
      note: form.value.note.trim() || undefined,
    }
    let saved: MemberWorkConstraintResponse
    if (props.userId === null) {
      saved = await upsertTeamDefault(props.teamId, payload)
    } else {
      saved = await upsertConstraint(props.teamId, props.userId, payload)
    }
    success(t('shift.workConstraint.saveSuccess'))
    emit('saved', saved)
  } catch (error) {
    handleApiError(error)
  } finally {
    saving.value = false
  }
}

async function remove() {
  deleting.value = true
  try {
    if (props.userId === null) {
      await deleteTeamDefault(props.teamId)
    } else {
      await deleteConstraint(props.teamId, props.userId)
    }
    success(t('shift.workConstraint.deleteSuccess'))
    emit('deleted')
  } catch (error) {
    handleApiError(error)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-4 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900">
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <!-- 月最大勤務時間 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.maxMonthlyHours') }}</label>
        <InputNumber
          v-model="form.maxMonthlyHours"
          :min="0"
          :max="744"
          :placeholder="t('shift.workConstraint.noLimit')"
          class="w-full"
          suffix="h"
        />
      </div>

      <!-- 月最大勤務日数 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.maxMonthlyDays') }}</label>
        <InputNumber
          v-model="form.maxMonthlyDays"
          :min="0"
          :max="31"
          :placeholder="t('shift.workConstraint.noLimit')"
          class="w-full"
          :suffix="t('shift.workConstraint.days')"
        />
      </div>

      <!-- 最大連続勤務日数 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.maxConsecutiveDays') }}</label>
        <InputNumber
          v-model="form.maxConsecutiveDays"
          :min="1"
          :max="31"
          :placeholder="t('shift.workConstraint.noLimit')"
          class="w-full"
          :suffix="t('shift.workConstraint.days')"
        />
      </div>

      <!-- 月最大夜勤回数 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.maxNightShifts') }}</label>
        <InputNumber
          v-model="form.maxNightShiftsPerMonth"
          :min="0"
          :max="31"
          :placeholder="t('shift.workConstraint.noLimit')"
          class="w-full"
          :suffix="t('shift.workConstraint.times')"
        />
      </div>

      <!-- シフト間最小休憩時間 -->
      <div class="sm:col-span-2">
        <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.minRestHours') }}</label>
        <InputNumber
          v-model="form.minRestHoursBetweenShifts"
          :min="0"
          :max="72"
          :step="0.5"
          :placeholder="t('shift.workConstraint.noLimit')"
          class="w-full"
          suffix="h"
        />
      </div>
    </div>

    <!-- メモ -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('shift.workConstraint.note') }}</label>
      <InputText v-model="form.note" class="w-full" :placeholder="t('shift.workConstraint.notePlaceholder')" />
    </div>

    <!-- ボタン -->
    <div class="flex justify-end gap-2">
      <Button
        v-if="constraint"
        :label="t('common.delete')"
        icon="pi pi-trash"
        severity="danger"
        text
        :loading="deleting"
        @click="remove"
      />
      <Button
        :label="t('common.save')"
        icon="pi pi-check"
        :loading="saving"
        @click="save"
      />
    </div>
  </div>
</template>
