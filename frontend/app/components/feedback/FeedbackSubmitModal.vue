<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })

const { t } = useI18n()
const notification = useNotification()
const feedbackApi = useFeedbackApi()

const form = ref({
  category: '',
  title: '',
  body: '',
  isAnonymous: false,
})
const submitting = ref(false)

const categoryOptions = computed(() => [
  { label: t('feedback.category.bug'), value: 'BUG' },
  { label: t('feedback.category.feature'), value: 'FEATURE' },
  { label: t('feedback.category.improvement'), value: 'IMPROVEMENT' },
  { label: t('feedback.category.other'), value: 'OTHER' },
])

const isValid = computed(
  () => form.value.category !== '' && form.value.title.trim() !== '' && form.value.body.trim() !== '',
)

async function submit() {
  if (!isValid.value) return
  submitting.value = true
  try {
    await feedbackApi.createFeedback({
      scopeType: 'GENERAL',
      category: form.value.category,
      title: form.value.title.trim(),
      body: form.value.body.trim(),
      isAnonymous: form.value.isAnonymous,
    })
    notification.success(t('feedback.submit.success'))
    visible.value = false
  } catch {
    notification.error(t('feedback.submit.error'))
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    category: '',
    title: '',
    body: '',
    isAnonymous: false,
  }
  submitting.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('feedback.submit.title')"
    modal
    class="w-full max-w-lg"
    @hide="resetForm"
  >
    <div class="space-y-4">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('feedback.category.label') }}</label>
        <Select
          v-model="form.category"
          :options="categoryOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('feedback.category.placeholder')"
          class="w-full"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('feedback.title.label') }}</label>
        <InputText
          v-model="form.title"
          :placeholder="t('feedback.title.placeholder')"
          class="w-full"
          maxlength="200"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('feedback.body.label') }}</label>
        <Textarea
          v-model="form.body"
          :placeholder="t('feedback.body.placeholder')"
          class="w-full"
          rows="4"
          maxlength="2000"
        />
      </div>
      <div class="flex items-center gap-2">
        <Checkbox v-model="form.isAnonymous" input-id="feedback-anonymous" binary />
        <label for="feedback-anonymous" class="text-sm cursor-pointer">{{ t('feedback.anonymous.label') }}</label>
      </div>
    </div>

    <template #footer>
      <Button :label="t('button.cancel')" severity="secondary" @click="visible = false" />
      <Button
        :label="t('button.submit')"
        :loading="submitting"
        :disabled="!isValid"
        icon="pi pi-send"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
