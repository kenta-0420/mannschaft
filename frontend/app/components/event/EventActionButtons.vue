<script setup lang="ts">
defineProps<{
  status: string
}>()

defineEmits<{
  edit: []
  publish: []
  closeRegistration: []
  openRegistration: []
  cancel: []
}>()
</script>

<template>
  <div class="flex gap-2">
    <Button label="編集" icon="pi pi-pencil" outlined @click="$emit('edit')" />
    <Button
      v-if="status === 'DRAFT'"
      label="公開"
      icon="pi pi-send"
      severity="success"
      @click="$emit('publish')"
    />
    <Button
      v-if="status === 'PUBLISHED'"
      label="受付終了"
      icon="pi pi-lock"
      severity="warn"
      @click="$emit('closeRegistration')"
    />
    <Button
      v-if="status === 'CLOSED'"
      label="受付再開"
      icon="pi pi-lock-open"
      severity="info"
      @click="$emit('openRegistration')"
    />
    <Button
      v-if="status !== 'CANCELLED'"
      label="キャンセル"
      icon="pi pi-times"
      severity="danger"
      outlined
      @click="$emit('cancel')"
    />
  </div>
</template>
