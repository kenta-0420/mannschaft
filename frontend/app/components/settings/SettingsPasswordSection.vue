<script setup lang="ts">
const passwordForm = defineModel<{ currentPassword: string; newPassword: string; confirmPassword: string }>('passwordForm', { required: true })

defineProps<{
  hasPassword: boolean
  submittingPassword: boolean
  canSubmitPassword: boolean
  passwordError: string | null
}>()

defineEmits<{
  submit: []
}>()
</script>

<template>
  <SectionCard :title="hasPassword ? 'パスワード変更' : 'パスワード設定'">
    <div class="space-y-4">
      <p v-if="!hasPassword" class="text-sm text-surface-500">
        現在パスワードが設定されていません。パスワードを設定することでメール・パスワードでもログインできるようになります。
      </p>
      <div v-if="hasPassword">
        <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
        <Password
          v-model="passwordForm.currentPassword"
          :feedback="false"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">新しいパスワード</label>
        <Password
          v-model="passwordForm.newPassword"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">新しいパスワード（確認）</label>
        <Password
          v-model="passwordForm.confirmPassword"
          :feedback="false"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <p v-if="passwordError" class="text-sm text-red-500">{{ passwordError }}</p>
      <div class="flex justify-end">
        <Button
          :label="hasPassword ? 'パスワードを変更' : 'パスワードを設定'"
          icon="pi pi-lock"
          :loading="submittingPassword"
          :disabled="!canSubmitPassword"
          @click="$emit('submit')"
        />
      </div>
    </div>
  </SectionCard>
</template>
