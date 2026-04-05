<script setup lang="ts">
defineProps<{
  profile: {
    displayName: string
    email: string
    phoneNumber: string
    postalCode: string
    avatarUrl: string | null
    isSearchable: boolean
  }
  savingProfile: boolean
}>()

defineEmits<{
  save: []
  uploadAvatar: [event: Event]
}>()
</script>

<template>
  <SectionCard title="プロフィール情報">
    <div class="space-y-4">
      <div class="flex items-center gap-4">
        <div>
          <img
            v-if="profile.avatarUrl"
            :src="profile.avatarUrl"
            alt="アバター"
            class="h-20 w-20 rounded-full object-cover"
          />
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
            />
            <Button
              label="画像を変更"
              icon="pi pi-upload"
              severity="secondary"
              size="small"
              as="span"
            />
          </label>
          <p class="mt-1 text-xs text-surface-500">5MB以下のJPG, PNG</p>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">表示名</label>
        <InputText v-model="profile.displayName" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">電話番号</label>
        <InputText v-model="profile.phoneNumber" class="w-full" placeholder="090-0000-0000" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">郵便番号</label>
        <InputText v-model="profile.postalCode" class="w-full" placeholder="000-0000" />
      </div>
      <div
        class="flex items-center justify-between rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">ユーザー検索への表示</p>
          <p class="text-xs text-surface-500">
            オンにすると他のユーザーから検索で見つけられます
          </p>
        </div>
        <ToggleSwitch v-model="profile.isSearchable" />
      </div>
      <div class="flex justify-end">
        <Button label="保存" icon="pi pi-check" :loading="savingProfile" @click="$emit('save')" />
      </div>
    </div>
  </SectionCard>
</template>
