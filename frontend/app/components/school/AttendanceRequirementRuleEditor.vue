<script setup lang="ts">
import { ref, watch } from 'vue'
import type {
  AttendanceRequirementRule,
  CreateRequirementRuleRequest,
  RequirementCategory,
  UpdateRequirementRuleRequest,
} from '~/types/school'

const props = defineProps<{
  rule?: AttendanceRequirementRule
}>()

const emit = defineEmits<{
  save: [req: CreateRequirementRuleRequest | UpdateRequirementRuleRequest]
  cancel: []
}>()

const { t } = useI18n()

const categoryOptions: RequirementCategory[] = [
  'GRADE_PROMOTION',
  'GRADUATION',
  'SUBJECT_CREDIT',
  'PERFECT_ATTENDANCE',
  'CUSTOM',
]

// フォームの初期値を props から設定
function buildInitialForm(): CreateRequirementRuleRequest {
  if (props.rule) {
    return {
      organizationId: props.rule.organizationId,
      teamId: props.rule.teamId,
      termId: props.rule.termId,
      academicYear: props.rule.academicYear,
      category: props.rule.category,
      name: props.rule.name,
      description: props.rule.description,
      minAttendanceRate: props.rule.minAttendanceRate,
      maxAbsenceDays: props.rule.maxAbsenceDays,
      maxAbsenceRate: props.rule.maxAbsenceRate,
      countSickBayAsPresent: props.rule.countSickBayAsPresent,
      countSeparateRoomAsPresent: props.rule.countSeparateRoomAsPresent,
      countLibraryAsPresent: props.rule.countLibraryAsPresent,
      countOnlineAsPresent: props.rule.countOnlineAsPresent,
      countHomeLearningAsOfficialAbsence: props.rule.countHomeLearningAsOfficialAbsence,
      countLateAsAbsenceThreshold: props.rule.countLateAsAbsenceThreshold,
      warningThresholdRate: props.rule.warningThresholdRate,
      effectiveFrom: props.rule.effectiveFrom,
      effectiveUntil: props.rule.effectiveUntil,
    }
  }
  return {
    academicYear: new Date().getFullYear(),
    category: 'GRADE_PROMOTION',
    name: '',
    description: null,
    minAttendanceRate: null,
    maxAbsenceDays: null,
    maxAbsenceRate: null,
    countSickBayAsPresent: false,
    countSeparateRoomAsPresent: false,
    countLibraryAsPresent: false,
    countOnlineAsPresent: false,
    countHomeLearningAsOfficialAbsence: false,
    countLateAsAbsenceThreshold: 0,
    warningThresholdRate: null,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveUntil: null,
  }
}

const form = ref<CreateRequirementRuleRequest>(buildInitialForm())

// rule prop が変わったらフォームをリセット
watch(
  () => props.rule,
  () => {
    form.value = buildInitialForm()
  },
)

function onSave(): void {
  if (props.rule) {
    // 編集モード: UpdateRequirementRuleRequest として emit
    const req: UpdateRequirementRuleRequest = {
      name: form.value.name,
      description: form.value.description,
      minAttendanceRate: form.value.minAttendanceRate,
      maxAbsenceDays: form.value.maxAbsenceDays,
      maxAbsenceRate: form.value.maxAbsenceRate,
      countSickBayAsPresent: form.value.countSickBayAsPresent,
      countSeparateRoomAsPresent: form.value.countSeparateRoomAsPresent,
      countLibraryAsPresent: form.value.countLibraryAsPresent,
      countOnlineAsPresent: form.value.countOnlineAsPresent,
      countHomeLearningAsOfficialAbsence: form.value.countHomeLearningAsOfficialAbsence,
      countLateAsAbsenceThreshold: form.value.countLateAsAbsenceThreshold,
      warningThresholdRate: form.value.warningThresholdRate,
      effectiveFrom: form.value.effectiveFrom,
      effectiveUntil: form.value.effectiveUntil,
    }
    emit('save', req)
  } else {
    // 新規作成モード: CreateRequirementRuleRequest として emit
    emit('save', { ...form.value })
  }
}

function onCancel(): void {
  emit('cancel')
}
</script>

<template>
  <div
    data-testid="requirement-rule-editor"
    class="rounded-lg border border-primary-200 dark:border-primary-800 bg-primary-50 dark:bg-primary-950 p-4"
  >
    <h3 class="text-sm font-semibold text-primary-700 dark:text-primary-300 mb-4">
      {{ rule ? $t('school.requirement.editTitle') : $t('school.requirement.createTitle') }}
    </h3>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
      <!-- 規程名 -->
      <div class="md:col-span-2">
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.name') }} *
        </label>
        <InputText
          v-model="form.name"
          class="w-full"
          :placeholder="$t('school.requirement.name')"
        />
      </div>

      <!-- カテゴリ -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.category') }} *
        </label>
        <Select
          v-model="form.category"
          :options="categoryOptions"
          :option-label="(c: RequirementCategory) => $t(`school.requirement.category_${c}`)"
          class="w-full"
        />
      </div>

      <!-- 有効開始日 -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.effectiveFrom') }} *
        </label>
        <InputText
          v-model="form.effectiveFrom"
          type="date"
          class="w-full"
        />
      </div>

      <!-- 有効終了日（任意） -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.effectiveUntil') }}
        </label>
        <InputText
          v-model="form.effectiveUntil"
          type="date"
          class="w-full"
        />
      </div>

      <!-- 最低出席率 -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.minAttendanceRate') }}
        </label>
        <InputText
          v-model.number="form.minAttendanceRate"
          type="number"
          :min="0"
          :max="100"
          class="w-full"
        />
      </div>

      <!-- 最大欠席日数 -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.maxAbsenceDays') }}
        </label>
        <InputText
          v-model.number="form.maxAbsenceDays"
          type="number"
          :min="0"
          class="w-full"
        />
      </div>

      <!-- 警告しきい値 -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.warningThresholdRate') }}
        </label>
        <InputText
          v-model.number="form.warningThresholdRate"
          type="number"
          :min="0"
          :max="100"
          class="w-full"
        />
      </div>

      <!-- 遅刻N回で欠席換算 -->
      <div>
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.countLateAsAbsenceThreshold') }}
        </label>
        <InputText
          v-model.number="form.countLateAsAbsenceThreshold"
          type="number"
          :min="0"
          class="w-full"
        />
      </div>

      <!-- 説明（任意） -->
      <div class="md:col-span-2">
        <label class="text-xs text-surface-500 mb-1 block">
          {{ $t('school.requirement.description') }}
        </label>
        <Textarea
          v-model="form.description"
          class="w-full"
          rows="2"
          :placeholder="$t('school.requirement.description')"
        />
      </div>
    </div>

    <!-- チェックボックスグループ -->
    <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-4">
      <div class="flex items-center gap-2">
        <Checkbox
          v-model="form.countSickBayAsPresent"
          binary
          input-id="chk-sickbay"
        />
        <label
          for="chk-sickbay"
          class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer"
        >
          {{ $t('school.requirement.countSickBayAsPresent') }}
        </label>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox
          v-model="form.countSeparateRoomAsPresent"
          binary
          input-id="chk-separate"
        />
        <label
          for="chk-separate"
          class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer"
        >
          {{ $t('school.requirement.countSeparateRoomAsPresent') }}
        </label>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox
          v-model="form.countLibraryAsPresent"
          binary
          input-id="chk-library"
        />
        <label
          for="chk-library"
          class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer"
        >
          {{ $t('school.requirement.countLibraryAsPresent') }}
        </label>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox
          v-model="form.countOnlineAsPresent"
          binary
          input-id="chk-online"
        />
        <label
          for="chk-online"
          class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer"
        >
          {{ $t('school.requirement.countOnlineAsPresent') }}
        </label>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox
          v-model="form.countHomeLearningAsOfficialAbsence"
          binary
          input-id="chk-homelearning"
        />
        <label
          for="chk-homelearning"
          class="text-sm text-surface-700 dark:text-surface-300 cursor-pointer"
        >
          {{ $t('school.requirement.countHomeLearningAsOfficialAbsence') }}
        </label>
      </div>
    </div>

    <!-- ボタン -->
    <div class="flex gap-2 justify-end">
      <Button
        data-testid="cancel-requirement-rule-btn"
        :label="$t('school.requirement.cancel')"
        severity="secondary"
        size="small"
        @click="onCancel"
      />
      <Button
        data-testid="save-requirement-rule-btn"
        :label="$t('school.requirement.save')"
        :disabled="!form.name || !form.effectiveFrom"
        size="small"
        @click="onSave"
      />
    </div>
  </div>
</template>
