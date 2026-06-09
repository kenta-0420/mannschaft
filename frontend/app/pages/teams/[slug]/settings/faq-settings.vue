<script setup lang="ts">
/**
 * F21.1 §5.5: チーム FAQ 設定ページ。
 *
 * カテゴリ別固定 6 問 + 自由質問（最大 7 件）を編集する。
 * 認可（ADMIN / SYSTEM_ADMIN）・ロード・保存・トーストは FaqSettingsEditor が担う。
 *
 * 設計書: docs/features/F21.1_geo_optimization.md §5.5.6
 */
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div class="mx-auto max-w-2xl p-4">
    <FaqSettingsEditor
      scope-type="team"
      :scope-id="teamSlug"
    />
  </div>
</template>
