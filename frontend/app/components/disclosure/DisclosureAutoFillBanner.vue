<script setup lang="ts">
/**
 * 自動引用バナー（F09.14 Phase 2-β-5）。
 *
 * - 個人情報自動引用の許諾チェックボックス（規定値: false）
 * - 「自動引用を更新」ボタン → POST /{draftId}/refresh-auto-fill?allowPersonalInfo=
 *   - 成功時はドラフトを emit('refreshed', draft) で親に渡す（親は formData を更新）
 *
 * 注意書き: 所有者氏名など個人情報を引用する場合のみチェックさせる。
 */
import type { DisclosureFormDraft } from '~/types/disclosure'

const props = defineProps<{
  organizationId: number
  draftId: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  refreshed: [draft: DisclosureFormDraft]
}>()

const { t } = useI18n()
const { error: showError, success: showSuccess } = useNotification()

const allowPersonalInfo = ref(false)
const isRefreshing = ref(false)

const api = computed(() => useDisclosureApi(String(props.organizationId)))

async function refresh() {
  if (isRefreshing.value || props.disabled) return
  isRefreshing.value = true
  try {
    const draft = await api.value.refreshAutoFill(props.draftId, allowPersonalInfo.value)
    emit('refreshed', draft)
    showSuccess(t('disclosure.autoFill.refreshed'))
  } catch {
    showError(t('disclosure.autoFill.refreshFailed'))
  } finally {
    isRefreshing.value = false
  }
}
</script>

<template>
  <section
    class="rounded-md border border-blue-200 bg-blue-50 p-3 dark:border-blue-900 dark:bg-blue-950"
    data-testid="disclosure-auto-fill-banner"
  >
    <h3 class="mb-2 flex items-center gap-2 text-sm font-semibold text-blue-700 dark:text-blue-300">
      <i class="pi pi-info-circle" aria-hidden="true" />
      {{ t('disclosure.autoFill.title') }}
    </h3>
    <p class="mb-2 text-xs text-blue-700 dark:text-blue-200">
      {{ t('disclosure.autoFill.note') }}
    </p>
    <div class="flex flex-wrap items-center gap-3">
      <label class="flex items-center gap-2 text-sm font-medium">
        <Checkbox
          v-model="allowPersonalInfo"
          binary
          :disabled="disabled"
          data-testid="disclosure-allow-personal-info"
        />
        {{ t('disclosure.autoFill.allowPersonalInfo') }}
      </label>
      <Button
        :label="t('disclosure.autoFill.refresh')"
        icon="pi pi-refresh"
        :loading="isRefreshing"
        :disabled="disabled"
        size="small"
        severity="primary"
        data-testid="disclosure-auto-fill-refresh"
        @click="refresh"
      />
    </div>
  </section>
</template>
