<script setup lang="ts">
import { generatePassword } from '~/utils/passwordGenerator'

const props = defineProps<{ password: string; disabled?: boolean }>()
const emit = defineEmits<{ generated: [password: string] }>()
const { t } = useI18n()
const notification = useNotification()
const hydrated = useHydrated()
const copying = ref(false)
const unavailable = computed(() => props.disabled || !hydrated.value || copying.value)

function generate(): void {
  if (unavailable.value) return
  try {
    // 完成した値だけを渡す。途中の乱数取得が失敗してもフォームを変更しない。
    const password = generatePassword()
    emit('generated', password)
  } catch {
    notification.error(t('settings.password.generator.generate_error'))
  }
}

async function copy(): Promise<void> {
  if (unavailable.value || !props.password) return
  const password = props.password
  copying.value = true
  try {
    await navigator.clipboard.writeText(password)
    notification.success(t('settings.password.generator.copied'))
  } catch {
    notification.error(t('settings.password.generator.copy_error'))
  } finally {
    copying.value = false
  }
}
</script>

<template>
  <div class="min-w-0 space-y-2">
    <div class="flex flex-wrap gap-2">
      <Button
        type="button"
        :label="t('settings.password.generator.generate')"
        icon="pi pi-refresh"
        severity="secondary"
        outlined
        class="min-h-11 min-w-11"
        :disabled="unavailable"
        @click="generate"
      />
      <Button
        type="button"
        :label="t('settings.password.generator.copy')"
        icon="pi pi-copy"
        severity="secondary"
        outlined
        class="min-h-11 min-w-11"
        :disabled="unavailable || !password"
        :loading="copying"
        @click="copy"
      />
    </div>
    <p class="text-xs text-surface-500">{{ t('settings.password.generator.hint') }}</p>
  </div>
</template>
