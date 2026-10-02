<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / タイムラインタブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.9 (scope=VILLAGE)
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルはタイムラインパネル本体のみ。
 */
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))

const { village, perms } = useVillageContext()

/** タイムラインフィードの ref（投稿後にリフレッシュするため） */
const feedRef = ref<{ refresh: () => void } | null>(null)

/** 村長（HEADMAN）または長老（ELDER）の場合は管理権限あり */
const isAdmin = computed(() => perms.value.isAdmin)

function onPosted() {
  feedRef.value?.refresh()
}
</script>

<template>
  <div v-if="village" class="mx-auto max-w-2xl p-4 sm:p-6">
    <!-- 投稿フォーム（メンバーのみ表示） -->
    <TimelinePostForm
      v-if="village.isMember"
      scope-type="VILLAGE"
      :scope-id="villageId"
      class="mb-4"
      @posted="onPosted"
    />

    <!-- タイムラインフィード -->
    <TimelineFeed
      ref="feedRef"
      scope-type="VILLAGE"
      :scope-id="villageId"
      :can-pin="isAdmin"
      :can-delete-others="isAdmin"
    />
  </div>
</template>
