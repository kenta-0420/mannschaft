<script setup lang="ts">
import type { MemberProfile } from '~/types/member-profile'

defineProps<{
  profile: MemberProfile
  editable?: boolean
}>()

const emit = defineEmits<{
  edit: [profile: MemberProfile]
  delete: [id: number]
}>()
</script>

<template>
  <Card class="w-full">
    <template #content>
      <div class="flex items-center gap-4">
        <img
          v-if="profile.photoUrl"
          :src="profile.photoUrl"
          alt=""
          class="h-16 w-16 rounded-full object-cover"
        >
        <div v-else class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-xl text-primary">
          {{ profile.displayName.charAt(0) }}
        </div>
        <div class="flex-1">
          <div class="flex items-center gap-2">
            <p class="font-semibold">{{ profile.displayName }}</p>
            <Badge v-if="profile.memberNumber" :value="profile.memberNumber" severity="secondary" />
          </div>
          <p v-if="profile.position" class="text-sm text-surface-500">{{ profile.position }}</p>
          <p v-if="profile.bio" class="mt-1 text-sm text-surface-600 dark:text-surface-400">{{ profile.bio }}</p>
          <div v-if="Object.keys(profile.customFields).length > 0" class="mt-2 flex flex-wrap gap-2">
            <span
              v-for="(value, key) in profile.customFields"
              :key="key"
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-700"
            >
              {{ key }}: {{ value }}
            </span>
          </div>
        </div>
        <div v-if="editable" class="flex gap-1">
          <Button icon="pi pi-pencil" size="small" text severity="secondary" @click="emit('edit', profile)" />
          <Button icon="pi pi-trash" size="small" text severity="danger" @click="emit('delete', profile.id)" />
        </div>
      </div>
    </template>
  </Card>
</template>
