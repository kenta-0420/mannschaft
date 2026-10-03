<script setup lang="ts">
/**
 * F06.5 ブログ輸出ダイアログ（§6.3 / §7 #13）。
 *
 * - エントリをブログ記事（PRIVATE）へ輸出する。輸出後も元エントリは残る（AC-20）。
 * - 再輸出は BE が 409 を返す（MVP ブロック）。FE は 409 を「既に輸出済み」として案内する。
 */
import type { ExportToBlogRequest } from '~/types/reflection'

const props = defineProps<{
  visible: boolean
  entryId: string
  /** 既に輸出済みか（exportedBlogPostId != null）。輸出済みなら再輸出ボタンを無効化し案内を出す（409 回避・follow-up A④）。 */
  alreadyExported?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  exported: [blogPostId: number | null]
}>()

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()

const title = ref('')
const submitting = ref(false)

watch(
  () => props.visible,
  (open) => {
    if (open) title.value = ''
  },
)

function close() {
  emit('update:visible', false)
}

async function submit() {
  submitting.value = true
  try {
    const body: ExportToBlogRequest = { title: title.value.trim() || undefined }
    const res = await reflectionApi.exportToBlog(props.entryId, body)
    notification.success(t('reflection.export.success'))
    emit('exported', res.data?.id ?? null)
    close()
  }
  catch (e: unknown) {
    const status = (e as { response?: { status?: number }; statusCode?: number })?.response?.status
      ?? (e as { statusCode?: number })?.statusCode
    if (status === 409) {
      notification.error(t('reflection.export.already_exported'))
      emit('exported', null)
      close()
    }
    else {
      notification.error(t('reflection.export.failed'))
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="t('reflection.export.dialog_title')"
    :style="{ width: '460px', maxWidth: '95vw' }"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-3">
      <Message v-if="alreadyExported" severity="info" :closable="false" class="m-0">
        <span class="inline-flex items-center gap-1">
          <i class="pi pi-bookmark" />{{ t('reflection.export.already_exported') }}
        </span>
      </Message>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reflection.export.title_label') }}</label>
        <InputText
          v-model="title"
          :placeholder="t('reflection.export.title_placeholder')"
          class="w-full"
          :disabled="alreadyExported"
          maxlength="200"
        />
      </div>
    </div>

    <template #footer>
      <Button :label="t('reflection.common.cancel')" severity="secondary" text @click="close" />
      <Button
        :label="t('reflection.export.submit')"
        :loading="submitting"
        :disabled="alreadyExported"
        icon="pi pi-upload"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
