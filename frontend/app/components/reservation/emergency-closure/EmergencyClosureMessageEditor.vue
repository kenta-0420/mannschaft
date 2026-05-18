<script setup lang="ts">
const props = defineProps<{
  subject: string
  messageBody: string
}>()

const emit = defineEmits<{
  'update:subject': [value: string]
  'update:messageBody': [value: string]
}>()

const subjectModel = computed({
  get: () => props.subject,
  set: (v: string) => emit('update:subject', v),
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
        <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.subject') }}</label>
        <InputText v-model="subjectModel" class="w-full" :placeholder="$t('emergency_closure.placeholder.subject')" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.body') }}</label>
        <Textarea
          v-model="messageBodyModel"
          class="w-full"
          rows="8"
          :placeholder="$t('emergency_closure.placeholder.body')"
          auto-resize
        />
      </div>
    </div>
  </section>
</template>
