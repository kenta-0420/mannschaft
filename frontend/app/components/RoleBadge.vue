<script setup lang="ts">
const props = defineProps<{
  role: string
}>()

const { t } = useI18n()

const severityMap: Record<string, string> = {
  SYSTEM_ADMIN: 'danger',
  ADMIN: 'warn',
  DEPUTY_ADMIN: 'info',
  MEMBER: 'success',
  SUPPORTER: 'secondary',
  GUEST: 'contrast',
}

const getLabel = (role: string): string => {
  return t(`role.${role}`, role)
}

const severity = computed(() => severityMap[props.role] ?? 'secondary')
const label = computed(() => getLabel(props.role))
</script>

<template>
  <Tag :value="label" :severity="severity" />
</template>
