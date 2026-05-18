<script setup lang="ts">
import type { TagResponse, CreateQuickMemoRequest } from '~/types/quickMemo'

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ created: [] }>()

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()
const tagApi = useTagApi()

const form = ref({
  title: '',
  body: '',
  tagIds: [] as number[],
  showBody: false,
  showTags: false,
  showReminder: false,
})
const submitting = ref(false)
// PrimeVue InputText のコンポーネントインスタンス。実 DOM は $el で取得する
const titleInput = ref<{ $el?: HTMLInputElement } | null>(null)
const personalTags = ref<TagResponse[]>([])
const creatingTag = ref(false)

// モーダル表示時に autofocus
watch(visible, async (val) => {
  if (val) {
    await nextTick()
    titleInput.value?.$el?.focus()
    if (personalTags.value.length === 0) {
      loadTags()
    }
  }
})

async function loadTags() {
  try {
    const res = await tagApi.listTags('personal')
    personalTags.value = res.data
  } catch {
    // タグ取得失敗時は空のままフォールバック
  }
}

async function createTag(req: { name: string; color?: string }) {
  creatingTag.value = true
  try {
    const res = await tagApi.createTag('personal', undefined, req)
    personalTags.value.push(res.data)
    form.value.tagIds.push(res.data.id)
  } catch {
    notification.error(t('quick_memo.tag.create_error'))
  } finally {
    creatingTag.value = false
  }
}

async function submit() {
  const title = form.value.title.trim()
  if (!title) return
  submitting.value = true
  try {
    const body: CreateQuickMemoRequest = {
      title,
      body: form.value.body.trim() || undefined,
      tagIds: form.value.tagIds.length > 0 ? form.value.tagIds : undefined,
    }
    await memoApi.createMemo(body)
    notification.success(t('quick_memo.saved'))
    // 1.5秒後にモーダルを閉じる（連投しやすく）
    setTimeout(() => {
      visible.value = false
      submitting.value = false
      emit('created')
    }, 1500)
  } catch {
    notification.error(t('quick_memo.save_error'))
    submitting.value = false
  }
}

function onVoiceTranscript(text: string) {
  // タイトルが空ならまずタイトルへ。これで「ポイっと」ボタンの disabled が外れる
  if (!form.value.title.trim()) {
    // タイトルは 200 文字制限なので超過分は本文に回す
    const remain = 200 - form.value.title.length
    if (text.length <= remain) {
      form.value.title += text
      return
    }
    form.value.title += text.slice(0, remain)
    form.value.body += text.slice(remain)
  } else {
    form.value.body += text
  }
  form.value.showBody = form.value.body.length > 0
}

function resetForm() {
  form.value = {
    title: '',
    body: '',
    tagIds: [],
    showBody: false,
    showTags: false,
    showReminder: false,
  }
  submitting.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('quick_memo.capture_modal.title')"
    modal
    class="w-full max-w-lg"
    @hide="resetForm"
  >
    <div class="space-y-3">
      <!-- タイトル入力 -->
      <InputText
        ref="titleInput"
        v-model="form.title"
        :placeholder="t('quick_memo.capture_modal.title_placeholder')"
        class="w-full text-base"
        maxlength="200"
        autofocus
        @keydown.enter.prevent="submit"
      />

      <!-- 本文展開トグル -->
      <button
        v-if="!form.showBody"
        type="button"
        class="text-sm text-primary hover:underline"
        @click="form.showBody = true"
      >
        <i class="pi pi-plus-circle mr-1" />{{ t('quick_memo.capture_modal.add_body') }}
      </button>
      <Textarea
        v-else
        v-model="form.body"
        :placeholder="t('quick_memo.capture_modal.body_placeholder')"
        class="w-full"
        rows="4"
        maxlength="10000"
      />

      <!-- タグ展開トグル -->
      <button
        v-if="!form.showTags"
        type="button"
        class="text-sm text-primary hover:underline"
        @click="form.showTags = true"
      >
        <i class="pi pi-tag mr-1" />{{ t('quick_memo.form.add_tag') }}
      </button>
      <template v-else>
        <QuickMemoTagPicker
          v-model="form.tagIds"
          :tags="personalTags"
          :creating="creatingTag"
          @create="createTag"
        />
      </template>

      <!-- 音声入力: タイトルが空ならタイトルに、埋まっていれば本文に流し込む -->
      <div class="flex items-center gap-2">
        <QuickMemoVoiceInput @transcript="onVoiceTranscript" />
      </div>
    </div>

    <template #footer>
      <Button :label="t('button.cancel')" severity="secondary" @click="visible = false" />
      <Button
        :label="t('quick_memo.capture_modal.submit')"
        :loading="submitting"
        :disabled="!form.title.trim()"
        icon="pi pi-send"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
