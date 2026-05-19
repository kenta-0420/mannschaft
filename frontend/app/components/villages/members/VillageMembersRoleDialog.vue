<script setup lang="ts">
/**
 * ロール変更 Dialog — HEADMAN が他メンバーのロールを変更する UI。
 *
 * 親 (pages/villages/[id]/members.vue) から対象 membership と選択肢を受け取り、
 * 送信ボタン押下時は emit('submit') で親に処理を委譲する。
 *
 * - submitting 中はクローズ・操作を禁止する
 * - newRole は v-model:newRole で双方向束縛
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'

import type { MembershipResponse, VillageRole } from '~/types/village'

interface RoleOption {
  value: VillageRole
  label: string
}

const props = defineProps<{
  visible: boolean
  target: MembershipResponse | null
  newRole: VillageRole | null
  roleOptions: RoleOption[]
  submitting: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'update:newRole': [value: VillageRole | null]
  submit: []
  cancel: []
}>()

const { t } = useI18n()

const visibleModel = computed<boolean>({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

const newRoleModel = computed<VillageRole | null>({
  get: () => props.newRole,
  set: value => emit('update:newRole', value),
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
    :header="t('village.action.changeRole')"
    :style="{ width: '28rem' }"
    :closable="!submitting"
  >
    <div v-if="target" class="flex flex-col gap-4 py-2">
      <div class="text-sm">
        <span class="font-medium">{{ displayName(target) }}</span>
        <span class="ml-1 text-surface-500">
          ({{ t(`village.subjectType.${target.subjectType}`) }})
        </span>
      </div>

      <div>
        <label for="role-select" class="mb-1 block text-sm font-medium">
          {{ t('village.action.changeRole') }}
        </label>
        <Select
          id="role-select"
          v-model="newRoleModel"
          :options="roleOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          :disabled="submitting"
        />
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
        :label="t('village.action.save')"
        icon="pi pi-check"
        :disabled="submitting || !newRole"
        :loading="submitting"
        @click="emit('submit')"
      />
    </template>
  </Dialog>
</template>
