<script setup lang="ts">
import type {
  ScheduledSurveyOptionDraft,
  ScheduledSurveyQuestionDraft,
  ScheduleEventFormState,
} from './types'

const form = defineModel<ScheduleEventFormState>('form', { required: true })

const { t } = useI18n()

const resultsVisibilityOptions = computed(() => [
  { label: t('schedule.scheduled_survey.visibility_public'), value: 'PUBLIC' as const },
  { label: t('schedule.scheduled_survey.visibility_owner'), value: 'OWNER_ONLY' as const },
  { label: t('schedule.scheduled_survey.visibility_respondents'), value: 'RESPONDENTS' as const },
])

const questionTypeOptions = computed(() => [
  { label: t('schedule.scheduled_survey.qtype_single'), value: 'SINGLE_CHOICE' as const },
  { label: t('schedule.scheduled_survey.qtype_multiple'), value: 'MULTIPLE_CHOICE' as const },
])

const commentOptionOptions = computed(() => [
  { label: t('schedule.scheduled_attendance.comment_none'), value: 'NONE' as const },
  { label: t('schedule.scheduled_attendance.comment_optional'), value: 'OPTIONAL' as const },
  { label: t('schedule.scheduled_attendance.comment_required'), value: 'REQUIRED' as const },
])

function genKey(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function createOption(): ScheduledSurveyOptionDraft {
  return { key: genKey('opt'), optionText: '' }
}

function createQuestion(): ScheduledSurveyQuestionDraft {
  return {
    key: genKey('q'),
    questionText: '',
    questionType: 'SINGLE_CHOICE',
    isRequired: true,
    options: [createOption(), createOption()],
  }
}

function addQuestion() {
  form.value.scheduledSurvey.questions.push(createQuestion())
}

function removeQuestion(index: number) {
  form.value.scheduledSurvey.questions.splice(index, 1)
}

function addOption(question: ScheduledSurveyQuestionDraft) {
  question.options.push(createOption())
}

function removeOption(question: ScheduledSurveyQuestionDraft, index: number) {
  question.options.splice(index, 1)
}

// アンケート予約を有効化した瞬間に設問が空なら初期設問を1つ用意する
watch(
  () => form.value.scheduledSurvey.enabled,
  (enabled) => {
    if (enabled && form.value.scheduledSurvey.questions.length === 0) {
      form.value.scheduledSurvey.questions.push(createQuestion())
    }
  },
)
</script>

<template>
  <div class="flex flex-col gap-4">
    <!-- 予約アンケート作成 -->
    <div class="flex flex-col gap-3 rounded-lg border border-surface-200 dark:border-surface-600 p-3">
      <div class="flex items-center justify-between">
        <label class="text-sm font-medium">{{ $t('schedule.scheduled_survey.label') }}</label>
        <ToggleSwitch v-model="form.scheduledSurvey.enabled" />
      </div>

      <template v-if="form.scheduledSurvey.enabled">
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_survey.scheduled_at') }}</label>
          <DatePicker
            v-model="form.scheduledSurvey.scheduledAt"
            show-time
            hour-format="24"
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>

        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_survey.title_label') }}</label>
          <InputText
            v-model="form.scheduledSurvey.title"
            class="w-full"
            :placeholder="$t('schedule.scheduled_survey.title_placeholder')"
          />
        </div>

        <div class="flex items-center gap-3">
          <Checkbox
            v-model="form.scheduledSurvey.isAnonymous"
            :binary="true"
            input-id="scheduledSurveyAnon"
          />
          <label for="scheduledSurveyAnon" class="text-sm">
            {{ $t('schedule.scheduled_survey.anonymous') }}
          </label>
        </div>

        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_survey.results_visibility') }}</label>
          <Select
            v-model="form.scheduledSurvey.resultsVisibility"
            :options="resultsVisibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 設問ビルダー -->
        <div class="flex flex-col gap-3">
          <div
            v-for="(question, qIndex) in form.scheduledSurvey.questions"
            :key="question.key"
            class="flex flex-col gap-2 rounded-md border border-surface-200 dark:border-surface-700 p-2"
          >
            <div class="flex items-start gap-2">
              <InputText
                v-model="question.questionText"
                class="flex-1"
                :placeholder="$t('schedule.scheduled_survey.question_placeholder')"
              />
              <Button
                icon="pi pi-trash"
                text
                severity="danger"
                :aria-label="$t('schedule.common_delete')"
                @click="removeQuestion(qIndex)"
              />
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <Select
                v-model="question.questionType"
                :options="questionTypeOptions"
                option-label="label"
                option-value="value"
                class="w-40"
              />
              <label class="flex items-center gap-2 text-sm">
                <Checkbox v-model="question.isRequired" :binary="true" />
                {{ $t('schedule.scheduled_survey.required') }}
              </label>
            </div>

            <!-- 選択肢 -->
            <div class="flex flex-col gap-1.5 pl-2">
              <div
                v-for="(option, oIndex) in question.options"
                :key="option.key"
                class="flex items-center gap-2"
              >
                <InputText
                  v-model="option.optionText"
                  class="flex-1"
                  :placeholder="$t('schedule.scheduled_survey.option_placeholder')"
                />
                <Button
                  icon="pi pi-times"
                  text
                  severity="secondary"
                  size="small"
                  :disabled="question.options.length <= 2"
                  :aria-label="$t('schedule.common_delete')"
                  @click="removeOption(question, oIndex)"
                />
              </div>
              <Button
                icon="pi pi-plus"
                :label="$t('schedule.scheduled_survey.add_option')"
                text
                size="small"
                class="self-start"
                @click="addOption(question)"
              />
            </div>
          </div>

          <Button
            icon="pi pi-plus"
            :label="$t('schedule.scheduled_survey.add_question')"
            text
            size="small"
            class="self-start"
            @click="addQuestion"
          />
        </div>
      </template>
    </div>

    <!-- 予約出欠募集作成 -->
    <div class="flex flex-col gap-3 rounded-lg border border-surface-200 dark:border-surface-600 p-3">
      <div class="flex items-center justify-between">
        <label class="text-sm font-medium">{{ $t('schedule.scheduled_attendance.label') }}</label>
        <ToggleSwitch v-model="form.scheduledAttendance.enabled" />
      </div>

      <template v-if="form.scheduledAttendance.enabled">
        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_attendance.scheduled_at') }}</label>
          <DatePicker
            v-model="form.scheduledAttendance.scheduledAt"
            show-time
            hour-format="24"
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>

        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_attendance.deadline') }}</label>
          <DatePicker
            v-model="form.scheduledAttendance.attendanceDeadline"
            show-time
            hour-format="24"
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>

        <div class="flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ $t('schedule.scheduled_attendance.comment_option') }}</label>
          <Select
            v-model="form.scheduledAttendance.commentOption"
            :options="commentOptionOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </template>
    </div>
  </div>
</template>
