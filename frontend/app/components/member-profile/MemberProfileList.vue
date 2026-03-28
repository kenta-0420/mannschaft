<script setup lang="ts">
import type { MemberProfile } from '~/types/member-profile'

defineProps<{
  profiles: MemberProfile[]
  editable?: boolean
}>()

const emit = defineEmits<{
  edit: [profile: MemberProfile]
  delete: [id: number]
  create: []
}>()
</script>

<template>
  <div>
    <div v-if="editable" class="mb-4 flex justify-end">
      <Button label="メンバー追加" icon="pi pi-plus" size="small" @click="emit('create')" />
    </div>

    <div v-if="profiles.length === 0" class="py-12 text-center text-surface-500">
      <i class="pi pi-users mb-2 text-4xl" />
      <p>メンバーが登録されていません</p>
    </div>

    <div v-else class="grid gap-4 md:grid-cols-2">
      <MemberProfileCard
        v-for="profile in profiles"
        :key="profile.id"
        :profile="profile"
        :editable="editable"
        @edit="emit('edit', $event)"
        @delete="emit('delete', $event)"
      />
    </div>
  </div>
</template>
