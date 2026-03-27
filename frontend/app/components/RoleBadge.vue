<script setup lang="ts">
const props = defineProps<{
  role: string
}>()

const severityMap: Record<string, string> = {
  SYSTEM_ADMIN: 'danger',
  ADMIN: 'warn',
  DEPUTY_ADMIN: 'info',
  MEMBER: 'success',
  SUPPORTER: 'secondary',
  GUEST: 'contrast',
}

const labelMap: Record<string, string> = {
  SYSTEM_ADMIN: 'システム管理者',
  ADMIN: '管理者',
  DEPUTY_ADMIN: '副管理者',
  MEMBER: 'メンバー',
  SUPPORTER: 'サポーター',
  GUEST: 'ゲスト',
}

const severity = computed(() => severityMap[props.role] ?? 'secondary')
const label = computed(() => labelMap[props.role] ?? props.role)
</script>

<template>
  <Tag :value="label" :severity="severity" />
</template>
