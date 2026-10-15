<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  locked: boolean
  lockedByTitle?: string | null
}>()

const { t } = useI18n()

const tooltipText = computed(() =>
  props.lockedByTitle ? t('project.locked_by', { title: props.lockedByTitle }) : '',
)
</script>

<template>
  <Tag
    v-if="locked"
    v-tooltip="tooltipText"
    severity="warning"
    icon="pi pi-lock"
    :value="$t('project.locked')"
  />
</template>
