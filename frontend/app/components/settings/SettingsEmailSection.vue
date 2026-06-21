<script setup lang="ts">
const emailForm = defineModel<{ newEmail: string; currentPassword: string }>('emailForm', { required: true })

defineProps<{
  currentEmail: string
  submittingEmail: boolean
  emailSent: boolean
  canSubmitEmail: boolean
}>()

defineEmits<{
  submit: []
}>()
</script>

<template>
  <SectionCard :title="$t('settings.email.section_title')">
    <template v-if="!emailSent">
      <div class="space-y-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('settings.email.current_email') }}</label>
          <InputText :model-value="currentEmail" class="w-full" disabled />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('settings.email.new_email') }}</label>
          <InputText
            v-model="emailForm.newEmail"
            type="email"
            class="w-full"
            :placeholder="$t('settings.email.new_email_placeholder')"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('settings.email.current_password') }}</label>
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
            translate="no"
            :label="$t('settings.email.send_confirmation')"
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
        <p class="mb-1 font-semibold">{{ $t('settings.email.sent_title') }}</p>
        <p class="text-sm text-surface-500">
          {{ $t('settings.email.sent_description', { email: emailForm.newEmail }) }}
        </p>
      </div>
    </template>
  </SectionCard>
</template>
