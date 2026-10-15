<script setup lang="ts">
/**
 * BAN 確認 Dialog — HEADMAN が他メンバーを村から BAN するための確認 UI。
 *
 * 親 (pages/villages/[id]/members.vue) から対象 membership・送信制御フラグ・
 * 理由文字列を受け取り、送信は emit('submit') で委譲する。
 *
 * - reason は v-model:reason で双方向束縛
 * - submitting 中はクローズ・操作を禁止する
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Textarea from 'primevue/textarea'

import type { MembershipResponse } from '~/types/village'

const props = defineProps<{
  visible: boolean
  target: MembershipResponse | null
  reason: string
  reasonError: string | null
  reasonMax: number
  canSubmit: boolean
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'update:reason': [value: string]
  submit: []
  cancel: []
}>()

const { t } = useI18n()

const visibleModel = computed<boolean>({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

const reasonModel = computed<string>({
  get: () => props.reason,
  set: value => emit('update:reason', value),
})

function displayName(m: MembershipResponse): string {
  return m.displayName ?? `#${m.subjectId}`
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    modal
    :draggable="false"
    :header="t('village.action.ban')"
    :style="{ width: '32rem' }"
    :closable="!submitting"
  >
    <div v-if="target" class="flex flex-col gap-4 py-2">
      <div class="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800 dark:border-red-800 dark:bg-red-950 dark:text-red-200">
        <i class="pi pi-exclamation-triangle mr-1" />
        {{ t('village.members.banned') }}: {{ displayName(target) }}
      </div>

      <div>
        <label for="ban-reason" class="mb-1 block text-sm font-medium">
          {{ t('village.report.dialog.detail') }}
        </label>
        <Textarea
          id="ban-reason"
          v-model="reasonModel"
          :maxlength="reasonMax"
          :auto-resize="true"
          rows="3"
          class="w-full"
          :invalid="!!reasonError"
          :disabled="submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ reason.length }} / {{ reasonMax }}
        </p>
        <p v-if="reasonError" class="mt-1 text-xs text-red-600">
          {{ reasonError }}
        </p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="emit('cancel')"
      />
      <Button
        :label="t('village.action.ban')"
        icon="pi pi-ban"
        severity="danger"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="emit('submit')"
      />
    </template>
  </Dialog>
</template>
