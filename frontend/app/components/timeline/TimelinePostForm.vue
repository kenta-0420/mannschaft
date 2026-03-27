<script setup lang="ts">
import type { TimelineScopeType } from '~/types/timeline'

const props = defineProps<{
  scopeType: TimelineScopeType
  scopeId?: number
}>()

const emit = defineEmits<{
  posted: []
}>()

const { createPost } = useTimelineApi()
const { showSuccess, showError } = useNotification()

const content = ref('')
const images = ref<File[]>([])
const videoUrl = ref('')
const showPollDialog = ref(false)
const poll = ref<{ question: string; options: string[]; expiresAt?: string } | null>(null)
const submitting = ref(false)

const maxLength = computed(() => props.scopeType === 'PUBLIC' ? 280 : 5000)

const canSubmit = computed(() => {
  return content.value.trim().length > 0
    && content.value.length <= maxLength.value
    && !submitting.value
})

function onFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files) return
  const newFiles = Array.from(input.files)
  const total = images.value.length + newFiles.length
  if (total > 4) {
    showError('画像は最大4枚まで添付できます')
    return
  }
  images.value.push(...newFiles.slice(0, 4 - images.value.length))
  input.value = ''
}

function removeImage(index: number) {
  images.value.splice(index, 1)
}

function onPollCreated(p: { question: string; options: string[]; expiresAt?: string }) {
  poll.value = p
}

function removePoll() {
  poll.value = null
}

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const formData = new FormData()
    formData.append('scope_type', props.scopeType)
    if (props.scopeId) formData.append('scope_id', String(props.scopeId))
    formData.append('content', content.value.trim())
    images.value.forEach(img => formData.append('images', img))
    if (videoUrl.value.trim()) {
      formData.append('video_urls', videoUrl.value.trim())
    }
    if (poll.value) {
      formData.append('poll', JSON.stringify(poll.value))
    }

    await createPost(formData)
    showSuccess('投稿しました')
    content.value = ''
    images.value = []
    videoUrl.value = ''
    poll.value = null
    emit('posted')
  } catch {
    showError('投稿に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="rounded-xl border border-surface-200 bg-surface-0 p-4">
    <Textarea
      v-model="content"
      :placeholder="scopeType === 'PUBLIC' ? '今どうしてる？' : 'チームに投稿...'"
      auto-resize
      rows="3"
      class="mb-2 w-full"
    />

    <!-- 文字数カウンター -->
    <div class="mb-2 flex justify-end">
      <span
        class="text-xs"
        :class="content.length > maxLength ? 'text-red-500' : 'text-surface-400'"
      >
        {{ content.length }} / {{ maxLength }}
      </span>
    </div>

    <!-- 画像プレビュー -->
    <div v-if="images.length > 0" class="mb-3 flex flex-wrap gap-2">
      <div v-for="(img, i) in images" :key="i" class="relative">
        <img :src="URL.createObjectURL(img)" class="h-20 w-20 rounded-lg object-cover" />
        <button
          class="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-xs text-white"
          @click="removeImage(i)"
        >
          <i class="pi pi-times text-[8px]" />
        </button>
      </div>
    </div>

    <!-- 投票プレビュー -->
    <div v-if="poll" class="mb-3 rounded-lg border border-primary/30 bg-primary/5 p-3">
      <div class="flex items-center justify-between">
        <div>
          <p class="text-sm font-medium">{{ poll.question }}</p>
          <p class="text-xs text-surface-400">{{ poll.options.length }}択</p>
        </div>
        <Button icon="pi pi-times" text rounded severity="danger" size="small" @click="removePoll" />
      </div>
    </div>

    <!-- アクションバー -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-1">
        <label class="cursor-pointer">
          <input type="file" accept="image/*" multiple class="hidden" @change="onFileSelect" />
          <Button icon="pi pi-image" text rounded severity="secondary" size="small" as="span" />
        </label>
        <Button
          v-if="scopeType !== 'PUBLIC'"
          icon="pi pi-chart-bar"
          text
          rounded
          severity="secondary"
          size="small"
          :disabled="!!poll"
          @click="showPollDialog = true"
        />
      </div>
      <Button
        label="投稿"
        size="small"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="onSubmit"
      />
    </div>

    <TimelinePollForm v-model:visible="showPollDialog" @created="onPollCreated" />
  </div>
</template>
