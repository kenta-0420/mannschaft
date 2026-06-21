<script setup lang="ts">
import type { SocialProfile, CreateSocialProfileRequest } from '~/types/social-profile'

const socialForm = defineModel<CreateSocialProfileRequest>('socialForm', { required: true })

defineProps<{
  socialProfiles: SocialProfile[]
  showSocialDialog: boolean
  editingSocialProfile: SocialProfile | null
}>()

defineEmits<{
  createSocial: []
  editSocial: [profile: SocialProfile]
  deleteSocial: [id: number]
  saveSocial: []
  'update:showSocialDialog': [value: boolean]
}>()
</script>

<template>
  <SectionCard>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ $t('settings.social_profile.section_title') }}</h2>
      <Button
        v-if="socialProfiles.length < 3"
        translate="no"
        :label="$t('settings.social_profile.create_button')"
        icon="pi pi-plus"
        size="small"
        @click="$emit('createSocial')"
      />
    </div>
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('settings.social_profile.count_description', { count: socialProfiles.length }) }}
    </p>
    <div class="space-y-4">
      <SocialProfileCard
        v-for="p in socialProfiles"
        :key="p.id"
        :profile="p"
        :show-actions="true"
        @edit="$emit('editSocial', $event)"
        @delete="$emit('deleteSocial', $event)"
      />
      <div v-if="socialProfiles.length === 0" class="py-8 text-center text-surface-500">
        <i class="pi pi-user-plus mb-2 text-4xl" />
        <p>{{ $t('settings.social_profile.no_profiles') }}</p>
      </div>
    </div>
  </SectionCard>

  <Dialog
    :visible="showSocialDialog"
    :header="editingSocialProfile ? $t('settings.social_profile.dialog_title_edit') : $t('settings.social_profile.dialog_title_create')"
    :modal="true"
    class="w-full max-w-md"
    @update:visible="$emit('update:showSocialDialog', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.social_profile.handle_label') }}</label>
        <InputText
          v-model="socialForm.handle"
          class="w-full"
          :placeholder="$t('settings.social_profile.handle_placeholder')"
          :disabled="!!editingSocialProfile"
        />
        <p class="mt-1 text-xs text-surface-500">{{ $t('settings.social_profile.handle_hint') }}</p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.social_profile.display_name_label') }}</label>
        <InputText v-model="socialForm.displayName" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.social_profile.bio_label') }}</label>
        <Textarea v-model="socialForm.bio" class="w-full" rows="3" :maxlength="300" />
        <p class="mt-1 text-right text-xs text-surface-400">
          {{ $t('settings.social_profile.bio_count', { count: socialForm.bio?.length ?? 0 }) }}
        </p>
      </div>
    </div>
    <template #footer>
      <Button
        translate="no"
        :label="$t('button.cancel')"
        severity="secondary"
        @click="$emit('update:showSocialDialog', false)"
      />
      <Button
        translate="no"
        :label="editingSocialProfile ? $t('settings.social_profile.update_button') : $t('settings.social_profile.create_submit_button')"
        icon="pi pi-check"
        :disabled="!socialForm.handle || !socialForm.displayName"
        @click="$emit('saveSocial')"
      />
    </template>
  </Dialog>
</template>
