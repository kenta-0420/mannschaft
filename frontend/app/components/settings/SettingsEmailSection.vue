<script setup lang="ts">
defineProps<{
  currentEmail: string
  emailForm: { newEmail: string; currentPassword: string }
  submittingEmail: boolean
  emailSent: boolean
  canSubmitEmail: boolean
}>()

defineEmits<{
  submit: []
}>()
</script>

<template>
  <SectionCard title="メールアドレス変更">
    <template v-if="!emailSent">
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">現在のメールアドレス</label>
          <InputText :model-value="currentEmail" class="w-full" disabled />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">新しいメールアドレス</label>
          <InputText
            v-model="emailForm.newEmail"
            type="email"
            class="w-full"
            placeholder="new@example.com"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">現在のパスワード</label>
          <Password
            v-model="emailForm.currentPassword"
            :feedback="false"
            toggle-mask
            class="w-full"
            input-class="w-full"
          />
        </div>
        <div class="flex justify-end">
          <Button
            label="確認メールを送信"
            icon="pi pi-envelope"
            :loading="submittingEmail"
            :disabled="!canSubmitEmail"
            @click="$emit('submit')"
          />
        </div>
      </div>
    </template>
    <template v-else>
      <div class="py-6 text-center">
        <i class="pi pi-check-circle mb-3 text-5xl text-green-500" />
        <p class="mb-1 font-semibold">確認メールを送信しました</p>
        <p class="text-sm text-surface-500">
          {{
            emailForm.newEmail
          }}
          に確認メールを送信しました。リンクをクリックして変更を完了してください。
        </p>
      </div>
    </template>
  </SectionCard>
</template>
