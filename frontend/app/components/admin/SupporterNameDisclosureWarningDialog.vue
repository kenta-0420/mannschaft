<script setup lang="ts">
/**
 * F19.1 Phase 2: 投稿者識別モード切替警告ダイアログ。
 *
 * DISPLAY_NAME → REAL_NAME への切替時のみ、外部拡散の不可逆性に関する警告を表示する。
 * ユーザーがチェックボックスをオンにしてから「確認して切り替える」ボタンが活性化される。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2
 */
const props = defineProps<{
  modelValue: boolean
  currentMode: 'DISPLAY_NAME' | 'REAL_NAME'
  targetMode: 'DISPLAY_NAME' | 'REAL_NAME'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  cancel: []
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/** REAL_NAME への切替時のみ警告を表示する */
const isWarningRequired = computed(
  () => props.targetMode === 'REAL_NAME' && props.currentMode !== 'REAL_NAME',
)

const checked = ref(false)

/** ダイアログが開くたびにチェックをリセットする */
watch(
  () => props.modelValue,
  (open) => {
    if (open) checked.value = false
  },
)

function onConfirm() {
  emit('confirm')
  visible.value = false
}

function onCancel() {
  emit('cancel')
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('public.admin.nameDisclosure.title')"
    :style="{ width: '480px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <!-- REAL_NAME への切替時のみ警告を表示 -->
      <template v-if="isWarningRequired">
        <div class="rounded border border-orange-300 bg-orange-50 p-4">
          <p class="mb-1 font-semibold text-orange-800">
            {{ t('public.admin.nameDisclosure.warningTitle') }}
          </p>
          <p class="text-sm text-orange-700">
            {{ t('public.admin.nameDisclosure.warningBody') }}
          </p>
        </div>
        <div class="flex items-start gap-2">
          <Checkbox v-model="checked" binary input-id="disclosureConfirmCheckbox" />
          <label for="disclosureConfirmCheckbox" class="cursor-pointer text-sm">
            {{ t('public.admin.nameDisclosure.warningCheckbox') }}
          </label>
        </div>
      </template>

      <!-- REAL_NAME → DISPLAY_NAME への切替は確認なし -->
      <template v-else>
        <p class="text-sm text-gray-700">
          {{
            t('public.admin.nameDisclosure.displayName')
          }}
          に切り替えます。
        </p>
      </template>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="t('public.admin.nameDisclosure.cancelButton')"
          severity="secondary"
          @click="onCancel"
        />
        <Button
          :label="t('public.admin.nameDisclosure.confirmButton')"
          severity="warning"
          :disabled="isWarningRequired && !checked"
          @click="onConfirm"
        />
      </div>
    </template>
  </Dialog>
</template>
