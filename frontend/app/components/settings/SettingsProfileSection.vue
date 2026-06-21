<script setup lang="ts">
const profile = defineModel<{
  nickname: string
  email: string
  phoneNumber: string
  postalCode: string
  avatarUrl: string | null
  isSearchable: boolean
}>('profile', { required: true })

defineProps<{
  savingProfile: boolean
}>()

defineEmits<{
  save: []
  uploadAvatar: [event: Event]
}>()
</script>

<template>
  <SectionCard :title="$t('settings.settings.profile.section_title')">
    <div class="space-y-4">
      <div class="flex items-center gap-4">
        <div>
          <img
            v-if="profile.avatarUrl"
            :src="profile.avatarUrl"
            :alt="$t('settings.settings.profile.avatar_alt')"
            class="h-20 w-20 rounded-full object-cover"
          >
          <div
            v-else
            class="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 text-2xl text-primary"
          >
            <i class="pi pi-user" />
          </div>
        </div>
        <div>
          <label class="cursor-pointer">
            <input
              type="file"
              accept="image/*"
              class="hidden"
              @change="$emit('uploadAvatar', $event)"
            >
            <Button
              translate="no"
              :label="$t('settings.settings.profile.change_image')"
              icon="pi pi-upload"
              severity="secondary"
              size="small"
              as="span"
            />
          </label>
          <p class="mt-1 text-xs text-surface-500">{{ $t('settings.settings.profile.image_hint') }}</p>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.profile.display_name') }}</label>
        <InputText v-model="profile.nickname" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.profile.phone_number') }}</label>
        <InputText v-model="profile.phoneNumber" class="w-full" :placeholder="$t('settings.settings.profile.phone_placeholder')" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.settings.profile.postal_code') }}</label>
        <InputText v-model="profile.postalCode" class="w-full" :placeholder="$t('settings.settings.profile.postal_placeholder')" />
      </div>
      <div
        class="flex items-center justify-between rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">{{ $t('settings.settings.profile.searchable_label') }}</p>
          <p class="text-xs text-surface-500">
            {{ $t('settings.settings.profile.searchable_description') }}
          </p>
        </div>
        <ToggleSwitch v-model="profile.isSearchable" />
      </div>
      <div class="flex justify-end">
        <Button translate="no" :label="$t('button.save')" icon="pi pi-check" :loading="savingProfile" @click="$emit('save')" />
      </div>
    </div>
  </SectionCard>
</template>
