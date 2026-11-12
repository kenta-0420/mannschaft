<script setup lang="ts">
import type { ChatChannelResponse } from '~/types/chat'
import { useChatCreatedChannel } from '~/composables/chat/useChatCreatedChannel'
import { useChatOpenCreatedChannel } from '~/composables/chat/useChatOpenCreatedChannel'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

// レスポンシブ: デスクトップ(>=768px)=2ペイン / モバイル=単一ペイン（一覧 ⇄ 会話）
const { isDesktop } = useIsDesktop()

const selectedChannel = ref<ChatChannelResponse | null>(null)
const chatListRef = ref<{ refresh: () => Promise<void>, selectIfPresent: (channelId: number) => boolean } | null>(null)
const showCreateDialog = ref(false)

// モバイル単一ペインで「一覧を出すか空状態を出すか」を決めるための件数。
// ChatChannelList の @loaded で更新する（一覧が0件なら aside を出さず空状態にする）。
const mobileChannelCount = ref(0)

/**
 * ?channel={id} で開いたときに選ぶチャンネル（問い合わせ通知の遷移先。CMP-261030-0648）。
 * 一覧の読み込みが済んだら渡すのをやめる（モバイルで一覧へ戻ったとき同じチャンネルを選び直さないため）。
 * 開いた後に query だけが変わる SPA 遷移（同じ slug ではページが作り直されない）は下の watch で追従する。
 */
function parseChannelQuery(value: unknown): number | undefined {
  const raw = Array.isArray(value) ? value[0] : value
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return undefined
  const id = Number(raw)
  return id > 0 ? id : undefined
}
const pendingChannelId = ref<number | undefined>(parseChannelQuery(route.query.channel))

function onChannelListLoaded(count: number) {
  mobileChannelCount.value = count
  pendingChannelId.value = undefined
}

/**
 * query の ?channel= が変わったら選択を追従させる。いったん未選択にしてから、
 * 一覧にある（見られる）チャンネルなら選び直す。正の整数でない・一覧に無いなら未選択のまま。
 * 一覧が描画されていない（モバイルで会話を表示中）ときは、一覧の再表示時に initialChannelId で選ぶ。
 *
 * 一覧の取り直しを待つ間に query がさらに変わることがある。要求ごとに番号を振り、
 * 取り直しが済んだ時点で最新の要求でなければ結果を捨てる（古い要求で URL と違う会話を開かない）。
 */
let channelQuerySeq = 0
watch(
  () => parseChannelQuery(route.query.channel),
  async (channelId) => {
    const seq = ++channelQuerySeq
    selectedChannel.value = null
    const list = chatListRef.value
    if (channelId === undefined || !list) {
      pendingChannelId.value = channelId
      return
    }
    // 読み込み中の一覧が古い initialChannelId で選ばないよう、渡す値を消してから取り直す
    pendingChannelId.value = undefined
    await list.refresh()
    if (seq !== channelQuerySeq) return
    list.selectIfPresent(channelId)
  },
)

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

const { resolveCreatedChannel } = useChatCreatedChannel()

/**
 * 作成系の応答（viewer を持たない）をそのまま表示中にせず、詳細 API で取り直してから表示する
 * （作成者が OWNER として操作できるように。CMP-261105-1507）。取り直しの失敗は通知される。
 */
async function onChannelCreated(ch: ChatChannelResponse) {
  chatListRef.value?.refresh()
  const detail = await resolveCreatedChannel(ch)
  if (detail) selectedChannel.value = detail
}

function onCreated() {
  chatListRef.value?.refresh()
}

/** 作成ダイアログが開始した DM・作成した Zimmer を右ペインに開く（CMP-261105-1507） */
const { openCreatedChannel } = useChatOpenCreatedChannel(chatListRef, (ch) => {
  selectedChannel.value = ch
})

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
            :initial-channel-id="pendingChannelId"
            @select="onChannelSelect"
            @create="showCreateDialog = true"
            @loaded="onChannelListLoaded"
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
              :initial-channel-id="pendingChannelId"
              @select="onChannelSelect"
              @create="showCreateDialog = true"
              @loaded="onChannelListLoaded"
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
      @open-dm="openCreatedChannel"
      @open-channel="openCreatedChannel"
    />
  </div>
</template>
