<script setup lang="ts">
/** BE（CreateEmergencyClosureRequest）の reason / subject の @Size(max=200) と一致させる。 */
const MAX_LENGTH = 200

const props = defineProps<{
  subject: string
  reason: string
  messageBody: string
  /** BE の 400 fieldErrors（フィールド名 → 表示メッセージ）。該当入力欄の下に表示する。 */
  fieldErrors?: Record<string, string>
}>()

const emit = defineEmits<{
  'update:subject': [value: string]
  'update:reason': [value: string]
  'update:messageBody': [value: string]
}>()

const subjectModel = computed({
  get: () => props.subject,
  set: (v: string) => emit('update:subject', v),
})
const reasonModel = computed({
  get: () => props.reason,
  set: (v: string) => emit('update:reason', v),
})
const messageBodyModel = computed({
  get: () => props.messageBody,
  set: (v: string) => emit('update:messageBody', v),
})
</script>

<template>
  <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
    <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.message_edit') }}</h3>
    <div class="space-y-3">
      <div>
        <label for="closure-reason" class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.reason') }}</label>
        <InputText
          id="closure-reason"
          v-model="reasonModel"
          data-testid="closure-reason"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors?.reason }"
          :maxlength="MAX_LENGTH"
          :placeholder="$t('emergency_closure.placeholder.reason')"
        />
        <small v-if="fieldErrors?.reason" data-testid="closure-reason-error" class="text-red-500">{{ fieldErrors.reason }}</small>
      </div>
      <div>
        <label for="closure-subject" class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.subject') }}</label>
        <InputText
          id="closure-subject"
          v-model="subjectModel"
          data-testid="closure-subject"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors?.subject }"
          :maxlength="MAX_LENGTH"
          :placeholder="$t('emergency_closure.placeholder.subject')"
        />
        <small v-if="fieldErrors?.subject" data-testid="closure-subject-error" class="text-red-500">{{ fieldErrors.subject }}</small>
      </div>
      <div>
        <label for="closure-body" class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.body') }}</label>
        <Textarea
          id="closure-body"
          v-model="messageBodyModel"
          data-testid="closure-body"
          class="w-full"
          rows="8"
          :placeholder="$t('emergency_closure.placeholder.body')"
          auto-resize
        />
      </div>
    </div>
  </section>
</template>
