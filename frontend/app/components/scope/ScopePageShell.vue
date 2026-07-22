<script setup lang="ts">
import type { Component } from 'vue'
import type { ScopeTab } from '~/types/scopeShell'

/**
 * スコープ詳細「永続シェル」共通骨格（UI のみ）。
 *
 * # 責務
 *  ヘッダ（#header slot）＋ ルート連動タブバー ＋ 任意のサイドバー Drawer ＋
 *  本文（default slot: 親が <NuxtPage/> を渡す）を常駐描画する。
 *  データ取得・権限判定は一切持たない（親ページ / 各子タブの責務）。
 *
 * # 金型
 *  タブバーは VillageHeader.vue L391-411 のルート連動方式を移植:
 *    <Tabs :value="activeTab"><Tab as="div"><NuxtLink :to>
 *  これにより <NuxtPage/> だけが差し替わり、タブ遷移で白画面が出ない。
 *
 * # 注意
 *  pageTransition(out-in) はルート直下の単一「要素」を要求する。テンプレートの
 *  ルートは単一 <div> で始める（白画面回帰防止）。
 */
const props = withDefaults(
  defineProps<{
    /** 表示するタブ配列（visible!==false のもののみ描画）。 */
    tabs: ScopeTab[]
    /** 現在アクティブなタブ key（親がルート末尾セグメントから導出して渡す）。 */
    activeTab: string
    /** サイドバーに描画するコンポーネント（null 許容: 村など不要な場合）。 */
    sidebar?: Component | null
    /** サイドバーコンポーネントへ渡す props。 */
    sidebarProps?: Record<string, unknown>
    /** 管理者/メンバーレンズトグルを表示するか。 */
    showLens?: boolean
    /** レンズ状態（true=管理者ビュー / false=メンバービュー）。 */
    lens?: boolean
  }>(),
  {
    sidebar: null,
    sidebarProps: () => ({}),
    showLens: false,
    lens: false,
  },
)

const emit = defineEmits<{
  'update:lens': [value: boolean]
}>()

const route = useRoute()

/** 描画対象のタブ（visible が明示的に false のものを除外）。 */
const visibleTabs = computed<ScopeTab[]>(() =>
  props.tabs.filter(tab => tab.visible !== false),
)

/** サイドバー Drawer の開閉。ルート変更時に閉じる（layouts/team.vue L13-15 のパターン）。 */
const showSidebarDrawer = ref(false)
watch(() => route.path, () => {
  showSidebarDrawer.value = false
})

/** レンズ v-model 配線。 */
const lensModel = computed<boolean>({
  get: () => props.lens ?? false,
  set: (value: boolean) => emit('update:lens', value),
})
</script>

<template>
  <div>
    <!-- ヘッダ（親が TeamPageHeader 等を注入） -->
    <slot name="header" />

    <!-- 任意の注意書き（凍結案内など） -->
    <slot name="notice" />

    <!-- ============================================================== -->
    <!-- タブナビ（ルート連動 / 村方式）                                   -->
    <!-- ============================================================== -->
    <div class="border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <div class="flex items-center">
        <div class="flex-1 min-w-0 overflow-x-auto">
          <Tabs :value="activeTab">
            <TabList>
              <Tab
                v-for="tab in visibleTabs"
                :key="tab.key"
                :value="tab.key"
                as="div"
                class="scope-shell__tab"
              >
                <NuxtLink
                  :to="tab.to"
                  class="flex items-center gap-2 min-h-11 no-underline text-inherit"
                >
                  <i :class="tab.icon" />
                  <span>{{ $t(tab.labelKey) }}</span>
                </NuxtLink>
              </Tab>
            </TabList>
          </Tabs>
        </div>

        <!-- サイドバー起動ハンバーガー（sidebar 非 null のときのみ） -->
        <div v-if="sidebar" class="shrink-0 px-1">
          <Button
            icon="pi pi-bars"
            text
            rounded
            size="small"
            :aria-label="$t('common.menu')"
            @click="showSidebarDrawer = true"
          />
        </div>

        <!-- 管理者/メンバーレンズトグル -->
        <div v-if="showLens" class="shrink-0 px-3">
          <ScopeLensToggle v-model="lensModel" />
        </div>
      </div>
    </div>

    <!-- タブ本体（子）— 永続シェル下で差し替え -->
    <div class="px-6 pb-6">
      <slot />
    </div>

    <!-- サイドバー Drawer -->
    <Drawer
      v-if="sidebar"
      v-model:visible="showSidebarDrawer"
      position="left"
      class="!w-72"
    >
      <template #header>
        <span class="font-semibold">{{ $t('common.menu') }}</span>
      </template>
      <component :is="sidebar" v-bind="sidebarProps" />
    </Drawer>
  </div>
</template>
