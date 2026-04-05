<script setup lang="ts">
import type { SocialProfile, CreateSocialProfileRequest } from '~/types/social-profile'

defineProps<{
  socialProfiles: SocialProfile[]
  showSocialDialog: boolean
  editingSocialProfile: SocialProfile | null
  socialForm: CreateSocialProfileRequest
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
      <h2 class="text-lg font-semibold">ソーシャルプロフィール</h2>
      <Button
        v-if="socialProfiles.length < 3"
        label="新規作成"
        icon="pi pi-plus"
        size="small"
        @click="$emit('createSocial')"
      />
    </div>
    <p class="mb-4 text-sm text-surface-500">
      最大3つのプロフィールを作成できます（{{ socialProfiles.length }}/3）
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
        <p>まだプロフィールがありません</p>
      </div>
    </div>
  </SectionCard>

  <Dialog
    :visible="showSocialDialog"
    :header="editingSocialProfile ? 'プロフィール編集' : 'プロフィール作成'"
    :modal="true"
    class="w-full max-w-md"
    @update:visible="$emit('update:showSocialDialog', $event)"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">ハンドル名 *</label>
        <InputText
          v-model="socialForm.handle"
          class="w-full"
          placeholder="my_handle"
          :disabled="!!editingSocialProfile"
        />
        <p class="mt-1 text-xs text-surface-500">英数字とアンダースコアのみ（変更は30日に1回）</p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">表示名 *</label>
        <InputText v-model="socialForm.displayName" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">自己紹介</label>
        <Textarea v-model="socialForm.bio" class="w-full" rows="3" :maxlength="300" />
        <p class="mt-1 text-right text-xs text-surface-400">
          {{ socialForm.bio?.length ?? 0 }}/300
        </p>
      </div>
    </div>
    <template #footer>
      <Button
        label="キャンセル"
        severity="secondary"
        @click="$emit('update:showSocialDialog', false)"
      />
      <Button
        :label="editingSocialProfile ? '更新' : '作成'"
        icon="pi pi-check"
        :disabled="!socialForm.handle || !socialForm.displayName"
        @click="$emit('saveSocial')"
      />
    </template>
  </Dialog>
</template>
