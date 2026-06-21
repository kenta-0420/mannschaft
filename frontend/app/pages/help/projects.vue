<script setup lang="ts">
/**
 * プロジェクト機能 使い方説明ページ。
 *
 * 直リンク（/help/projects）用に存続するページ。
 * コンテンツは ProjectGuideContent コンポーネントに集約し、モーダルと共用する。
 */

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const router = useRouter()

useSeoMeta({
  title: () => `${t('project.guide.page_title')} | Mannschaft`,
  description: () => t('project.guide.description'),
})
</script>

<template>
  <main class="mx-auto w-full max-w-[860px] px-4 py-8 md:px-6">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center gap-3">
      <Button
        icon="pi pi-arrow-left"
        text
        rounded
        :aria-label="t('common.button.back')"
        @click="router.back()"
      />
      <h1 class="text-2xl font-bold text-surface-900 dark:text-white">
        {{ t('project.guide.page_title') }}
      </h1>
    </div>

    <p class="mb-8 text-surface-600 dark:text-surface-300">
      {{ t('project.guide.description') }}
    </p>

    <!-- 説明コンテンツ（モーダルと共用） -->
    <ProjectGuideContent />

    <!-- フッター：ページに戻る -->
    <div class="mt-8 flex justify-center">
      <Button
        :label="t('project.guide.back_to_projects')"
        icon="pi pi-arrow-left"
        text
        @click="router.back()"
      />
    </div>
  </main>
</template>
