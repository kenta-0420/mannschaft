<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  triggered: []
}>()

const safetyApi = useSafetyCheckApi()
const notification = useNotification()

const submitting = ref(false)
const form = ref({
  title: '',
  description: '',
  isDrill: false,
})

async function submit() {
  if (!form.value.title.trim()) return
  submitting.value = true
  try {
    await safetyApi.triggerSafetyCheck(props.scopeType, props.scopeId, {
      title: form.value.title.trim(),
      description: form.value.description.trim() || undefined,
      isDrill: form.value.isDrill,
    })
    notification.success(form.value.isDrill ? '【訓練】安否確認を発動しました' : '安否確認を発動しました')
    emit('triggered')
    close()
  }
  catch { notification.error('安否確認の発動に失敗しました') }
  finally { submitting.value = false }
}

function close() {
  emit('update:visible', false)
  form.value = { title: '', description: '', isDrill: false }
}
</script>

<template>
  <Dialog :visible="visible" header="安否確認を発動" :style="{ width: '450px' }" modal @update:visible="close">
    <div class="flex flex-col gap-4">
      <div class="rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-800 dark:bg-red-900/20">
        <p class="text-sm font-medium text-red-700 dark:text-red-300">
          <i class="pi pi-exclamation-triangle mr-1" />
          この操作は全メンバーに通知が送信されます
        </p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
        <InputText v-model="form.title" class="w-full" placeholder="例: 地震発生に伴う安否確認" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">詳細説明</label>
        <Textarea v-model="form.description" rows="3" class="w-full" placeholder="状況の説明（任意）" />
      </div>
      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="form.isDrill" />
        <label class="text-sm">訓練モード（【訓練】が付与されます）</label>
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="form.isDrill ? '【訓練】発動する' : '発動する'"
        icon="pi pi-exclamation-triangle"
        severity="danger"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
