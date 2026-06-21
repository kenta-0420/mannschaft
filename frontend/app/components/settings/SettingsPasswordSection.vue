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
  <SectionCard :title="hasPassword ? $t('settings.password.section_title_change') : $t('settings.password.section_title_set')">
    <div class="space-y-4">
      <p v-if="!hasPassword" class="text-sm text-surface-500">
        {{ $t('settings.password.no_password_description') }}
      </p>
      <div v-if="hasPassword">
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.password.current_password') }}</label>
        <Password
          v-model="passwordForm.currentPassword"
          :feedback="false"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.password.new_password') }}</label>
        <Password
          v-model="passwordForm.newPassword"
          toggle-mask
          class="w-full"
          input-class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.password.confirm_password') }}</label>
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
          translate="no"
          :label="hasPassword ? $t('settings.password.change_button') : $t('settings.password.set_button')"
          icon="pi pi-lock"
          :loading="submittingPassword"
          :disabled="!canSubmitPassword"
          @click="$emit('submit')"
        />
      </div>
    </div>
  </SectionCard>
</template>
