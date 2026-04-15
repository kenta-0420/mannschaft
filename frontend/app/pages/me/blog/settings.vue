<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

interface BlogSettings {
  displayName: string | null
  bio: string | null
  avatarUrl: string | null
  theme: string | null
}

interface SelfReviewSettings {
  enabled: boolean
  reviewHours: number
}

const { getMyBlogSettings, updateMyBlogSettings } = useBlogApi()
const { handleError } = useErrorHandler()
const { success } = useNotification()

const settings = ref<BlogSettings>({
  displayName: null,
  bio: null,
  avatarUrl: null,
  theme: null,
})
const selfReview = ref<SelfReviewSettings>({ enabled: false, reviewHours: 24 })
const loading = ref(true)
const saving = ref(false)

async function loadSettings() {
  loading.value = true
  try {
    const res = await getMyBlogSettings()
    settings.value = { ...res.data }
  } catch (error) {
    handleError(error)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await updateMyBlogSettings(settings.value)
    success('ブログ設定を保存しました')
  } catch (error) {
    handleError(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => loadSettings())
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-8">
    <div class="mb-6 flex items-center gap-3">
      <BackButton to="/me/blog" />
      <PageHeader :title="$t('blog.post.settings')" />
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <SectionCard class="mb-6">
        <template #title>{{ $t('blog.post.profileSettings') }}</template>

        <div class="space-y-4">
          <!-- 表示名 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
              表示名
            </label>
            <InputText
              v-model="settings.displayName"
              class="w-full"
              placeholder="ブログ上での表示名"
            />
          </div>

          <!-- 自己紹介 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
              自己紹介
            </label>
            <Textarea
              v-model="settings.bio"
              class="w-full"
              rows="3"
              placeholder="ブログの自己紹介文"
            />
          </div>

          <!-- アバターURL -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
              アバターURL
            </label>
            <InputText
              v-model="settings.avatarUrl"
              class="w-full"
              placeholder="https://..."
            />
            <img
              v-if="settings.avatarUrl"
              :src="settings.avatarUrl"
              alt="アバタープレビュー"
              class="mt-2 h-16 w-16 rounded-full object-cover"
            />
          </div>
        </div>
      </SectionCard>

      <!-- セルフレビュー設定 -->
      <SectionCard class="mb-6">
        <template #title>{{ $t('blog.post.selfReview') }}</template>

        <p class="mb-4 text-sm text-surface-500">
          {{ $t('blog.post.selfReviewDesc') }}
        </p>

        <div class="space-y-4">
          <div class="flex items-center justify-between">
            <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
              セルフレビューを有効にする
            </label>
            <ToggleSwitch v-model="selfReview.enabled" />
          </div>

          <div v-if="selfReview.enabled" class="flex flex-col gap-1">
            <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
              見直し時間（時間）
            </label>
            <InputNumber
              v-model="selfReview.reviewHours"
              :min="1"
              :max="168"
              class="w-full"
              suffix=" 時間"
            />
            <p class="text-xs text-surface-400">
              公開申請後、この時間が経過してから自動公開されます（最大7日）
            </p>
          </div>
        </div>
      </SectionCard>

      <!-- 保存ボタン -->
      <div class="flex justify-end">
        <Button
          :label="$t('blog.post.saveSettings')"
          icon="pi pi-save"
          :loading="saving"
          @click="save"
        />
      </div>
    </template>
  </div>
</template>
