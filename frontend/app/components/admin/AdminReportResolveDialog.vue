<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })
const form = defineModel<{ actionType: string; note: string; guidelineSection: string }>('form', { required: true })

const emit = defineEmits<{
  resolve: []
}>()

const actionOptions = [
  { label: '警告', value: 'WARNING' },
  { label: 'コンテンツ削除', value: 'CONTENT_DELETE' },
  { label: 'アカウント凍結', value: 'ACCOUNT_FREEZE' },
]
</script>

<template>
  <Dialog
    v-model:visible="visible"
    header="レポート解決"
    :style="{ width: '500px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">アクション種別</label>
        <Select
          v-model="form.actionType"
          :options="actionOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">メモ</label>
        <Textarea v-model="form.note" rows="3" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">ガイドラインセクション</label>
        <InputText v-model="form.guidelineSection" class="w-full" />
      </div>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <Button label="キャンセル" severity="secondary" @click="visible = false" />
        <Button label="解決する" severity="success" @click="emit('resolve')" />
      </div>
    </template>
  </Dialog>
</template>
