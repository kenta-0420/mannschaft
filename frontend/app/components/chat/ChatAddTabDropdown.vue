<script setup lang="ts">
import { onClickOutside } from '@vueuse/core'
import type { ChatChannelResponse } from '~/types/chat'

const props = defineProps<{
  /** true=スマホ（Bottom Sheet） / false=PC（ドロップダウン） */
  isMobile: boolean
}>()

const emit = defineEmits<{
  select: [channel: ChatChannelResponse]
  close: []
}>()

// ─── サブタブ管理 ──────────────────────────────────────────────
type SubTab = 'channels' | 'contacts' | 'requests'

const SESSION_KEY = 'chatTabsAddDropdown:lastTab'

function readLastTab(): SubTab {
  try {
    const v = sessionStorage.getItem(SESSION_KEY)
    if (v === 'channels' || v === 'contacts' || v === 'requests') return v
  } catch {
    // sessionStorage が使えない環境ではデフォルト値を返す
  }
  return 'channels'
}

const activeSubTab = ref<SubTab>(readLastTab())

function selectSubTab(tab: SubTab) {
  activeSubTab.value = tab
  try {
    sessionStorage.setItem(SESSION_KEY, tab)
  } catch {
    // ignore
  }
}

// ─── チャンネル選択 ────────────────────────────────────────────
function onChannelSelect(channel: ChatChannelResponse) {
  emit('select', channel)
}

// ─── PC版: 外クリックで閉じる ──────────────────────────────────
const dropdownRef = ref<HTMLElement | null>(null)

onClickOutside(dropdownRef, () => {
  if (!props.isMobile) {
    emit('close')
  }
})

// ─── スマホ版: Bottom Sheet の表示制御 ────────────────────────
const sheetVisible = ref(false)

onMounted(() => {
  if (props.isMobile) {
    // マウント後に少し遅延させてアニメーションを開始
    requestAnimationFrame(() => {
      sheetVisible.value = true
    })
  }
})

function closeSheet() {
  sheetVisible.value = false
  // トランジション完了後に emit
  setTimeout(() => {
    emit('close')
  }, 300)
}

// ─── i18n ─────────────────────────────────────────────────────
const { t } = useI18n()
</script>

<template>
  <!-- ===== スマホ版: Bottom Sheet ===== -->
  <template v-if="isMobile">
    <!-- オーバーレイ -->
    <div
      class="fixed inset-0 z-40 bg-black/50 transition-opacity duration-300"
      :class="sheetVisible ? 'opacity-100' : 'opacity-0'"
      @click="closeSheet"
    />

    <!-- Bottom Sheet 本体 -->
    <div
      class="fixed inset-x-0 bottom-0 z-50 flex max-h-[85dvh] flex-col rounded-t-2xl bg-surface-0 shadow-xl transition-transform duration-300 dark:bg-surface-800"
      :class="sheetVisible ? 'translate-y-0' : 'translate-y-full'"
    >
      <!-- ドラッグハンドル -->
      <div class="flex justify-center pt-3 pb-1">
        <div class="h-1 w-10 rounded-full bg-surface-300 dark:bg-surface-600" />
      </div>

      <!-- ヘッダー -->
      <div class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-700">
        <span class="text-base font-semibold">{{ t('chat.tab.addNew') }}</span>
        <button
          class="flex items-center gap-1 rounded-md px-2 py-1 text-sm text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700 dark:hover:bg-surface-700 dark:hover:text-surface-200"
          @click="closeSheet"
        >
          <i class="pi pi-times text-xs" />
          <span>{{ t('chat.tab.close') }}</span>
        </button>
      </div>

      <!-- サブタブ -->
      <div class="flex border-b border-surface-200 dark:border-surface-700">
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'channels'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('channels')"
        >
          {{ t('chat.tab.subtab.channels') }}
        </button>
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'contacts'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('contacts')"
        >
          {{ t('chat.tab.subtab.contacts') }}
        </button>
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'requests'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('requests')"
        >
          {{ t('chat.tab.subtab.requests') }}
        </button>
      </div>

      <!-- コンテンツ -->
      <div class="min-h-0 flex-1 overflow-y-auto">
        <!-- チャンネルタブ -->
        <div v-if="activeSubTab === 'channels'" class="h-full">
          <ChatChannelList @select="onChannelSelect" />
        </div>

        <!-- 連絡先タブ（プレースホルダー） -->
        <div
          v-else-if="activeSubTab === 'contacts'"
          class="flex h-full items-center justify-center py-16 text-sm text-surface-400"
        >
          {{ t('chat.tab.comingSoonContacts') }}
        </div>

        <!-- 申請タブ（プレースホルダー） -->
        <div
          v-else-if="activeSubTab === 'requests'"
          class="flex h-full items-center justify-center py-16 text-sm text-surface-400"
        >
          {{ t('chat.tab.comingSoonRequests') }}
        </div>
      </div>
    </div>
  </template>

  <!-- ===== PC版: ドロップダウン ===== -->
  <template v-else>
    <div
      ref="dropdownRef"
      class="w-80 overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-lg dark:border-surface-700 dark:bg-surface-800"
      style="max-height: 480px"
    >
      <!-- サブタブ -->
      <div class="flex border-b border-surface-200 dark:border-surface-700">
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'channels'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('channels')"
        >
          {{ t('chat.tab.subtab.channels') }}
        </button>
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'contacts'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('contacts')"
        >
          {{ t('chat.tab.subtab.contacts') }}
        </button>
        <button
          class="flex-1 px-3 py-2 text-sm font-medium transition-colors"
          :class="
            activeSubTab === 'requests'
              ? 'border-b-2 border-primary text-primary'
              : 'text-surface-500 hover:text-surface-700 dark:hover:text-surface-300'
          "
          @click="selectSubTab('requests')"
        >
          {{ t('chat.tab.subtab.requests') }}
        </button>
      </div>

      <!-- コンテンツ（スクロール可能） -->
      <div class="overflow-y-auto" style="max-height: 420px">
        <!-- チャンネルタブ -->
        <div v-if="activeSubTab === 'channels'">
          <ChatChannelList @select="onChannelSelect" />
        </div>

        <!-- 連絡先タブ（プレースホルダー） -->
        <div
          v-else-if="activeSubTab === 'contacts'"
          class="flex items-center justify-center py-16 text-sm text-surface-400"
        >
          {{ t('chat.tab.comingSoonContacts') }}
        </div>

        <!-- 申請タブ（プレースホルダー） -->
        <div
          v-else-if="activeSubTab === 'requests'"
          class="flex items-center justify-center py-16 text-sm text-surface-400"
        >
          {{ t('chat.tab.comingSoonRequests') }}
        </div>
      </div>
    </div>
  </template>
</template>
