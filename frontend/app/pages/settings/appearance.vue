<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const appearanceStore = useAppearanceStore()
const notification = useNotification()

const loading = ref(true)

// サーバーから初期読み込み
onMounted(async () => {
  try {
    await appearanceStore.loadFromServer()
  } finally {
    loading.value = false
  }
})

async function save() {
  await appearanceStore.syncWithServer()
  notification.success('外観設定を保存しました')
}
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else class="fade-in mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-2">
      <Button icon="pi pi-arrow-left" text rounded @click="navigateTo('/settings')" />
      <h1 class="text-2xl font-bold">外観設定</h1>
    </div>

    <div class="space-y-8">
      <!-- テーマ選択 -->
      <SectionCard>
        <ThemeSelector />
      </SectionCard>

      <!-- 背景色 -->
      <SectionCard>
        <BackgroundColorPicker />
      </SectionCard>

      <!-- チャットプレビュー設定 -->
      <SectionCard>
        <div class="flex items-center justify-between">
          <div>
            <label class="text-sm font-medium">チャットプレビュー非表示</label>
            <p class="text-xs text-surface-500">通知バナーでチャットの内容を表示しない</p>
          </div>
          <ToggleSwitch
            :model-value="appearanceStore.hideChatPreview"
            @update:model-value="(val: boolean) => appearanceStore.setHideChatPreview(val)"
          />
        </div>
      </SectionCard>

      <!-- プレビュー -->
      <SectionCard>
        <h3 class="mb-4 text-sm font-medium">プレビュー</h3>
        <div class="rounded-lg p-4" :style="{ backgroundColor: appearanceStore.bgColor }">
          <div
            class="rounded-lg border border-surface-200 bg-surface-0 p-4 shadow-sm dark:border-surface-700 dark:bg-surface-800"
          >
            <p class="text-sm font-medium">カードプレビュー</p>
            <p class="text-xs text-surface-500">選択した背景色とテーマが適用されます</p>
            <div class="mt-3 flex gap-2">
              <Tag value="タグ1" severity="info" />
              <Tag value="タグ2" severity="success" />
              <Tag value="タグ3" severity="warn" />
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 保存ボタン -->
      <div class="flex justify-end">
        <Button label="設定を保存" icon="pi pi-check" @click="save" />
      </div>
    </div>
  </div>
</template>
