<script setup lang="ts">
const props = defineProps<{
  scheduleId: number
}>()

const { t } = useI18n()
const { isDownloading, download } = useShiftPdf()

const dropdownItems = computed(() => [
  {
    label: t('shift.pdf.personalLayout'),
    icon: 'pi pi-user',
    command: () => download(props.scheduleId, 'personal'),
  },
])

async function onDefaultClick(): Promise<void> {
  await download(props.scheduleId, 'team')
}
</script>

<template>
  <SplitButton
    :label="isDownloading ? $t('shift.pdf.downloading') : $t('shift.pdf.teamLayout')"
    icon="pi pi-file-pdf"
    :loading="isDownloading"
    :disabled="isDownloading"
    :model="dropdownItems"
    severity="secondary"
    outlined
    size="small"
    @click="onDefaultClick"
  />
</template>
