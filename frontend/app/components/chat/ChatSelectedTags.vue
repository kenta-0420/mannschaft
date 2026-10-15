<script setup lang="ts">
import type { MemberResponse } from '~/types/member'

defineProps<{
  members: MemberResponse[]
}>()

const emit = defineEmits<{
  remove: [member: MemberResponse]
}>()
</script>

<template>
  <div v-if="members.length > 0" class="flex flex-wrap gap-1">
    <Tag
      v-for="m in members"
      :key="m.userId"
      :value="m.displayName"
      severity="info"
      class="cursor-pointer"
      @click="emit('remove', m)"
    >
      <template #default>
        {{ m.displayName }}
        <i class="pi pi-times ml-1 text-xs" />
      </template>
    </Tag>
  </div>
</template>
