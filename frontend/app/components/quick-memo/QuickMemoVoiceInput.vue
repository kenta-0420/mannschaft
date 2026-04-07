<script setup lang="ts">
const emit = defineEmits<{
  transcript: [text: string]
}>()

const { t } = useI18n()
const notification = useNotification()
const { isSupported, isListening, transcript, start, stop, reset } = useVoiceRecognition()
const { getActiveConsent, grantConsent, CURRENT_VERSION } = useVoiceInputConsent()

const showConsentDialog = ref(false)
const hasConsent = ref<boolean | null>(null)
const checking = ref(false)
const granting = ref(false)

// transcriptの変化をemit
watch(transcript, (val) => {
  if (val) emit('transcript', val)
})

async function handleClick() {
  if (!isSupported) return
  if (isListening.value) {
    stop()
    return
  }

  if (hasConsent.value === null) {
    checking.value = true
    try {
      const res = await getActiveConsent()
      hasConsent.value = res.data.hasConsent
    } catch {
      hasConsent.value = false
    } finally {
      checking.value = false
    }
  }

  if (hasConsent.value) {
    reset()
    start()
  } else {
    showConsentDialog.value = true
  }
}

async function handleGrantConsent() {
  granting.value = true
  try {
    await grantConsent(CURRENT_VERSION)
    hasConsent.value = true
    showConsentDialog.value = false
    reset()
    start()
  } catch {
    notification.error(t('quick_memo.voice.consent_error'))
  } finally {
    granting.value = false
  }
}
</script>

<template>
  <div v-if="isSupported">
    <Button
      type="button"
      :icon="isListening ? 'pi pi-stop-circle' : 'pi pi-microphone'"
      :severity="isListening ? 'danger' : 'secondary'"
      rounded
      :loading="checking"
      :title="isListening ? t('quick_memo.voice.stop') : t('quick_memo.voice.start')"
      @click="handleClick"
    />

    <!-- 録音中インジケーター -->
    <span v-if="isListening" class="ml-2 animate-pulse text-xs text-red-500">
      {{ t('quick_memo.voice.listening') }}
    </span>

    <!-- GDPR同意ダイアログ -->
    <Dialog
      v-model:visible="showConsentDialog"
      :header="t('quick_memo.voice.consent_title')"
      modal
      class="w-full max-w-md"
    >
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('quick_memo.voice.consent_body') }}
      </p>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          severity="secondary"
          @click="showConsentDialog = false"
        />
        <Button
          :label="t('quick_memo.voice.agree')"
          :loading="granting"
          @click="handleGrantConsent"
        />
      </template>
    </Dialog>
  </div>
</template>
