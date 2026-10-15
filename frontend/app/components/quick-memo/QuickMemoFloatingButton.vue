<script setup lang="ts">
const { t } = useI18n()
const capture = useQuickMemoCapture()

// ショートカット登録
onMounted(() => {
  capture.registerShortcut()
})
</script>

<template>
  <Button
    icon="pi pi-feather"
    rounded
    raised
    class="!fixed bottom-6 right-6 !h-14 !w-14 !shadow-lg z-50"
    :title="`${t('quick_memo.title')} (Ctrl+Shift+M)`"
    @click="capture.open()"
  />
</template>
