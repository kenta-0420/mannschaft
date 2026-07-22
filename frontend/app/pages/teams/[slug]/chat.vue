<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

// レスポンシブ: デスクトップ(>=768px)=2ペイン / モバイル=単一ペイン（一覧 ⇄ 会話）
const { isDesktop } = useIsDesktop()

const selectedChannel = ref<ChatChannelResponse | null>(null)
const chatListRef = ref<{ refresh: () => void } | null>(null)
const showCreateDialog = ref(false)

// モバイル単一ペインで「一覧を出すか空状態を出すか」を決めるための件数。
// ChatChannelList の @loaded で更新する（一覧が0件なら aside を出さず空状態にする）。
const mobileChannelCount = ref(0)

// 会話ビューのヘッダーに出す選択中チャンネル名
const selectedChannelName = computed(() => {
  const ch = selectedChannel.value
  if (!ch) return ''
  return ch.dmPartner?.displayName ?? ch.meta?.name ?? ''
})

function onChannelSelect(ch: ChatChannelResponse) {
  selectedChannel.value = ch
}

/** モバイル: 会話ビューから一覧ビューへ戻る */
function onBackToList() {
  selectedChannel.value = null
}

function onChannelCreated(ch: ChatChannelResponse) {
  selectedChannel.value = ch
  chatListRef.value?.refresh()
}

function onCreated() {
  chatListRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="チャット" />
    </div>

    <!--
      チャット本体コンテナ。
      - デスクトップ: 角丸のカード内に 2ペイン（既存 DOM を一切変更しない）。
      - モバイル: 単一ペインを画面幅いっぱい（full-bleed）で描画する。ScopePageShell の
        px-6 と AppShell の p-4 による左右余白を打ち消し、composer が横スクロールなしで
        使える幅（>=300px）を確保する。`w-screen + ml-[calc(50%-50vw)]` は対称パディング前提の
        自己補正フルブリード（余白量に依存しない）。
      - MCH-03 のセレクタ（div.border-2.border-surface-400 / dark:border-surface-500）を
        壊さないよう border クラスは両モードで維持する。
    -->
    <div
      class="flex h-[calc(100vh-12rem)] overflow-hidden border-2 border-surface-400 dark:border-surface-500"
      :class="isDesktop ? 'rounded-xl' : 'w-screen ml-[calc(50%_-_50vw)]'"
    >
      <!-- ===== デスクトップ: 2ペイン（既存レイアウト） ===== -->
      <template v-if="isDesktop">
        <!-- 左サイドバー（チャンネル一覧） -->
        <aside class="flex w-64 shrink-0 flex-col border-r border-surface-200 bg-surface-50 dark:border-surface-700 dark:bg-surface-800">
          <ChatChannelList
            ref="chatListRef"
            :team-id="teamSlug"
            @select="onChannelSelect"
            @create="showCreateDialog = true"
          />
        </aside>

        <!-- メインエリア -->
        <div class="flex-1 overflow-hidden bg-surface-0 dark:bg-surface-900">
          <ChatMessagePanel
            v-if="selectedChannel"
            :channel="selectedChannel"
            :can-pin="isAdminOrDeputy"
            :can-delete="isAdmin"
            :team-id="teamSlug"
            @channel-created="onChannelCreated"
          />
          <div v-else class="flex h-full flex-col items-center justify-center">
            <DashboardEmptyState icon="pi pi-comments" message="チャンネルを選択してください" />
          </div>
        </div>
      </template>

      <!-- ===== モバイル: 単一ペイン（一覧 ⇄ 会話） ===== -->
      <template v-else>
        <!-- 会話ビュー（チャンネル選択中） -->
        <div
          v-if="selectedChannel"
          class="flex min-w-0 flex-1 flex-col overflow-hidden bg-surface-0 dark:bg-surface-900"
        >
          <!-- チャット領域固有の「一覧へ戻る」導線（PageHeader の汎用戻るとは別物） -->
          <div class="flex items-center gap-2 border-b border-surface-200 px-2 py-1.5 dark:border-surface-700">
            <Button
              icon="pi pi-arrow-left"
              :aria-label="$t('chat.mobile.backToList')"
              text
              rounded
              size="small"
              @click="onBackToList"
            />
            <span class="min-w-0 truncate text-sm font-semibold">{{ selectedChannelName }}</span>
          </div>
          <div class="min-h-0 flex-1 overflow-hidden">
            <ChatMessagePanel
              :channel="selectedChannel"
              :can-pin="isAdminOrDeputy"
              :can-delete="isAdmin"
              :team-id="teamSlug"
              @channel-created="onChannelCreated"
            />
          </div>
        </div>

        <!-- 一覧ビュー（チャンネル未選択） -->
        <template v-else>
          <!-- 1件以上ある場合はチャンネル一覧（aside）を全幅表示 -->
          <aside
            v-show="mobileChannelCount > 0"
            class="flex min-w-0 flex-1 flex-col bg-surface-50 dark:bg-surface-800"
          >
            <ChatChannelList
              ref="chatListRef"
              :team-id="teamSlug"
              @select="onChannelSelect"
              @create="showCreateDialog = true"
              @loaded="mobileChannelCount = $event"
            />
          </aside>

          <!-- 0件の場合は aside を出さず、作成導線つきの空状態を表示 -->
          <div
            v-show="mobileChannelCount === 0"
            class="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center"
          >
            <i class="pi pi-comments text-4xl text-surface-400" aria-hidden="true" />
            <p class="text-sm text-surface-500">{{ $t('chat.mobile.noChannels') }}</p>
            <Button
              :label="$t('chat.mobile.createChannel')"
              icon="pi pi-plus"
              size="small"
              outlined
              @click="showCreateDialog = true"
            />
          </div>
        </template>
      </template>
    </div>

    <ChatCreateDialog
      v-model:visible="showCreateDialog"
      :team-id="teamSlug"
      @created="onCreated"
    />
  </div>
</template>
