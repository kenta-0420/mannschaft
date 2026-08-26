<script setup lang="ts">
const { getUnreadCount } = useNotificationApi()
const { getChannels } = useChatApi()
const api = useApi()
const router = useRouter()
const popover = ref()

interface Mention {
  id: number
  isRead: boolean
}

const notifCount = useState('badge:notifCount', () => 0)
const chatCount = ref(0)
const mentionCount = ref(0)

const totalCount = computed(() => notifCount.value + chatCount.value + mentionCount.value)

/**
 * useApi() の onResponseError が投げる「認証フロー起源」のエラーかどうかを判定する。
 *
 * useApi.ts の 401 ハンドラは、refresh_token が無効な本物のセッション失効を検出すると
 * 自律的に authStore.logout({ reason: 'session_expired' }) を実行し `/login` へ誘導した上で
 * Error('token_refresh_failed') / Error('not_authenticated') を投げる。この 2 つは
 * 「握りつぶすと症状を隠すことになる」認証エラーなので、ここでは飲み込まず再送出し
 * useApi 側の処理（＝既に実行済みのログイン誘導）を阻害しない。
 *
 * 一方 Error('token_refresh_transient')（回線が遅いだけの一時的失敗。ログアウトはしていない）
 * や、その他のネットワークエラー・5xx（useApi() がトースト集約・エラー報告済み）は
 * 通知ベルの件数取得としては非クリティカルなので静かに無視する。
 */
function isAuthFlowFailure(error: unknown): boolean {
  return (
    error instanceof Error
    && (error.message === 'token_refresh_failed' || error.message === 'not_authenticated')
  )
}

async function fetchCounts() {
  // バッジ件数取得（60 秒ポーリング）。3 種は独立しており、1 つが失敗しても
  // 他の件数は表示すべきなので allSettled で個別に処理する。
  // ただし認証エラー（isAuthFlowFailure）は握りつぶさない（症状を隠さない）。
  await Promise.allSettled([
    getUnreadCount()
      .then((r) => {
        notifCount.value = r.data.unreadCount
      })
      .catch((error: unknown) => {
        if (isAuthFlowFailure(error)) throw error
      }),
    getChannels()
      .then((r) => {
        chatCount.value = (r.data as { unreadCount?: number }[]).reduce(
          (sum: number, ch: { unreadCount?: number }) => sum + (ch.unreadCount ?? 0),
          0,
        )
      })
      .catch((error: unknown) => {
        if (isAuthFlowFailure(error)) throw error
      }),
    api<{ data: Mention[] }>('/api/v1/mentions')
      .then((r) => {
        mentionCount.value = r.data.filter((m: Mention) => !m.isRead).length
      })
      .catch((error: unknown) => {
        if (isAuthFlowFailure(error)) throw error
      }),
  ])
}

function onPopoverShow() {
  fetchCounts()
}

function navigate(to: string) {
  popover.value?.hide()
  router.push(to)
}

let timer: ReturnType<typeof setInterval>
onMounted(() => {
  fetchCounts()
  timer = setInterval(fetchCounts, 60000)
})
onUnmounted(() => clearInterval(timer))

defineExpose({ refresh: fetchCounts })
</script>

<template>
  <div class="relative">
    <Button
      v-tooltip.bottom="'通知'"
      icon="pi pi-bell"
      text
      rounded
      severity="secondary"
      @click="popover?.toggle($event)"
    />
    <Badge
      v-if="totalCount > 0"
      :value="totalCount > 99 ? '99+' : totalCount"
      severity="danger"
      class="absolute -right-1 -top-1 pointer-events-none shadow-md ring-2 ring-white dark:ring-surface-900 !min-w-[1.1rem] !h-[1.1rem] !text-[0.6rem]"
    />

    <Popover ref="popover" @show="onPopoverShow">
      <div class="flex flex-col gap-1 py-1" style="min-width: 200px">
        <!-- 通知 -->
        <button class="notif-row" @click="navigate('/notifications')">
          <span class="notif-icon bg-amber-50 text-amber-500"
            ><i class="pi pi-bell text-xs"
          /></span>
          <span class="flex-1 text-left text-sm">通知</span>
          <Badge v-if="notifCount > 0" :value="notifCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>

        <!-- チャット -->
        <button class="notif-row" @click="navigate('/chat')">
          <span class="notif-icon bg-green-50 text-green-500"
            ><i class="pi pi-comment text-xs"
          /></span>
          <span class="flex-1 text-left text-sm">チャット</span>
          <Badge v-if="chatCount > 0" :value="chatCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>

        <!-- メンション -->
        <button class="notif-row" @click="navigate('/notifications?tab=mention')">
          <span class="notif-icon bg-blue-50 text-blue-500"><i class="pi pi-at text-xs" /></span>
          <span class="flex-1 text-left text-sm">メンション</span>
          <Badge v-if="mentionCount > 0" :value="mentionCount" severity="danger" />
          <span v-else class="text-xs text-surface-400">なし</span>
        </button>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.notif-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
  transition: background 0.15s;
  cursor: pointer;
  background: transparent;
  border: none;
  width: 100%;
}
.notif-row:hover {
  background: var(--p-surface-100);
}
.notif-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
