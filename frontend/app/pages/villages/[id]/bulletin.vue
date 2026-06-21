<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 掲示板タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.9 (scope=VILLAGE)
 *
 * 永続シェル方式（SPA）への移行:
 *   - 村データ・メンバーシップ・権限・VillageHeader・join/leave/pin 等のアクションは
 *     親 `pages/villages/[id].vue` に集約。本ファイルは掲示板パネル本体のみを担う。
 *   - 村コンテキストは `useVillageContext()` で inject する。
 */
import type { BulletinThreadResponse } from '~/types/bulletin'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))

const { village, perms } = useVillageContext()

// =====================================================================
// 掲示板 State
// =====================================================================

/** 選択中のスレッド（詳細表示） */
const selectedThread = ref<BulletinThreadResponse | null>(null)
/** スレッド作成ダイアログ表示フラグ */
const showCreateDialog = ref(false)
/** BulletinThreadList の ref（スレッド保存後にリフレッシュするため） */
const listRef = ref<{ refresh: () => void } | null>(null)

/** 村長（HEADMAN）または長老（ELDER）の場合は管理権限あり */
const isAdmin = computed(() => perms.value.isAdmin)

function onSaved() {
  listRef.value?.refresh()
}
</script>

<template>
  <div v-if="village" class="mx-auto max-w-3xl p-4 sm:p-6">
    <!-- スレッド詳細（選択時） -->
    <div v-if="selectedThread" class="mx-auto max-w-3xl">
      <BulletinThreadDetail
        :thread-id="selectedThread.id"
        :can-manage="isAdmin"
        @back="selectedThread = null"
      />
    </div>

    <!-- スレッド一覧 -->
    <template v-else>
      <BulletinThreadList
        ref="listRef"
        scope-type="VILLAGE"
        :scope-id="villageId"
        :can-manage="isAdmin"
        @select="(thread) => selectedThread = thread"
        @create="showCreateDialog = true"
      />
    </template>

    <!-- ご縁ランキング (Phase 3) — 村人のみ表示 -->
    <section v-if="village.isMember && !selectedThread" class="mt-6 rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <VillageSerendipityRankingWidget :village-id="village.id" />
    </section>

    <!-- スレッド作成ダイアログ -->
    <BulletinThreadForm
      v-model:visible="showCreateDialog"
      scope-type="VILLAGE"
      :scope-id="villageId"
      @saved="onSaved"
    />
  </div>
</template>
