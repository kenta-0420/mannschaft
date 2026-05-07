<script setup lang="ts">
const props = defineProps<{
  loading?: boolean
}>()

const emit = defineEmits<{
  submit: [content: string]
}>()

const { t } = useI18n()
const content = ref('')

function submit() {
  const trimmed = content.value.trim()
  if (!trimmed || props.loading) return
  emit('submit', trimmed)
  content.value = ''
}

const isDisabled = computed(() => props.loading || content.value.trim().length === 0)
</script>

<template>
  <section
    class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
  >
    <h3 class="mb-2 text-sm font-semibold">{{ t('error_report.detail.comments') }}</h3>
    <Textarea
      v-model="content"
      :placeholder="t('error_report.detail.comment_placeholder')"
      rows="3"
      class="w-full"
      :maxlength="2000"
      :disabled="loading"
    />
    <div class="mt-2 flex items-center justify-between">
      <span class="text-xs text-surface-500">{{ content.length }}/2000</span>
      <Button
        :label="t('error_report.actions.save_comment')"
        icon="pi pi-comment"
        size="small"
        :loading="loading"
        :disabled="isDisabled"
        @click="submit"
      />
    </div>
  </section>
</template>
