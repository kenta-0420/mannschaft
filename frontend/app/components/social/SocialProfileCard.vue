<script setup lang="ts">
import type { SocialProfile } from '~/types/social-profile'

defineProps<{
  profile: SocialProfile
  showActions?: boolean
}>()

const emit = defineEmits<{
  edit: [profile: SocialProfile]
  delete: [id: number]
}>()
</script>

<template>
  <Card class="w-full">
    <template #content>
      <div class="flex items-center gap-4">
        <div class="relative">
          <img
            v-if="profile.avatarUrl"
            :src="profile.avatarUrl"
            alt=""
            class="h-16 w-16 rounded-full object-cover"
          />
          <div v-else class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-xl text-primary">
            <i class="pi pi-user" />
          </div>
          <div
            v-if="!profile.isActive"
            class="absolute inset-0 flex items-center justify-center rounded-full bg-black/50"
          >
            <i class="pi pi-lock text-white" />
          </div>
        </div>
        <div class="flex-1">
          <p class="font-semibold">{{ profile.displayName }}</p>
          <p class="text-sm text-surface-500">@{{ profile.handle }}</p>
          <p v-if="profile.bio" class="mt-1 text-sm text-surface-600 dark:text-surface-400">{{ profile.bio }}</p>
          <div class="mt-2 flex gap-4 text-xs text-surface-500">
            <span>{{ profile.followerCount }} フォロワー</span>
            <span>{{ profile.followingCount }} フォロー中</span>
          </div>
        </div>
        <div v-if="showActions" class="flex gap-1">
          <Button icon="pi pi-pencil" size="small" text severity="secondary" @click="emit('edit', profile)" />
          <Button icon="pi pi-trash" size="small" text severity="danger" @click="emit('delete', profile.id)" />
        </div>
      </div>
    </template>
  </Card>
</template>
