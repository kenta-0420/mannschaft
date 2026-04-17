<script setup lang="ts">
const { t } = useI18n()

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  todoId: number
}>()

const memoApi = useTodoMemo()
const notification = useNotification()

const memoText = ref('')
const loadingMemo = ref(false)

type SaveStatus = 'idle' | 'saving' | 'saved'
const saveStatus = ref<SaveStatus>('idle')
let debounceTimer: ReturnType<typeof setTimeout> | null = null

async function loadPersonalMemo() {
  loadingMemo.value = true
  try {
    const res = await memoApi.getPersonalMemo(props.scopeType, props.scopeId, props.todoId)
    memoText.value = res.data?.memo ?? ''
  } catch {
    // 未登録の場合は空文字のまま
    memoText.value = ''
  } finally {
    loadingMemo.value = false
  }
}

async function saveMemo() {
  saveStatus.value = 'saving'
  try {
    await memoApi.upsertPersonalMemo(props.scopeType, props.scopeId, props.todoId, {
      memo: memoText.value,
    })
    saveStatus.value = 'saved'
    // 3秒後に saved ステータスをリセット
    setTimeout(() => {
      saveStatus.value = 'idle'
    }, 3000)
  } catch {
    saveStatus.value = 'idle'
    notification.error(t('common.dialog.error'))
  }
}

function onInput() {
  saveStatus.value = 'idle'
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
  }
  debounceTimer = setTimeout(() => {
    saveMemo()
  }, 1000)
}

onMounted(loadPersonalMemo)
onBeforeUnmount(() => {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
  }
})
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between">
      <h3 class="font-semibold text-surface-800 dark:text-surface-100">
        {{ t('todo.enhancement.personal_memo.title') }}
      </h3>
      <!-- 保存ステータス -->
      <transition name="fade">
        <span v-if="saveStatus === 'saving'" class="text-xs text-surface-400">
          {{ t('todo.enhancement.personal_memo.saving') }}
        </span>
        <span v-else-if="saveStatus === 'saved'" class="text-xs text-green-500">
          <i class="pi pi-check mr-0.5" />{{ t('todo.enhancement.personal_memo.saved') }}
        </span>
      </transition>
    </div>

    <div v-if="loadingMemo">
      <Skeleton height="8rem" />
    </div>

    <textarea
      v-else
      v-model="memoText"
      rows="6"
      :placeholder="t('todo.enhancement.personal_memo.placeholder')"
      class="w-full resize-y rounded-lg border border-surface-300 bg-surface-0 p-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-600 dark:bg-surface-800"
      @input="onInput"
    />
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
