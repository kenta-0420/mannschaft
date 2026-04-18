<script setup lang="ts">
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 行動メモ 終業画面（Phase 2）。
 *
 * <p>設計書 §5.3 / §5.4 に基づく「前日夜の予定入力 + 終業時振り返り + タイムライン投稿」の
 * 3 機能統合画面。データ構造は以下:</p>
 *
 * <ul>
 *   <li><b>上部</b>: 当日のメモ一覧（{@link ActionMemoList} を流用）</li>
 *   <li><b>中部</b>: 自分の PERSONAL TODO を当日 {@code dueDate} でフィルタして
 *       「完了」「未完」2 列に分ける。未完 TODO には「明日に回す」ボタンを付与する</li>
 *   <li><b>下部</b>: 「明日やること」入力欄（POST {@code /api/v1/todos} dueDate=翌日）と
 *       {@code extra_comment} 入力欄 + 「今日を締める」ボタン（publishDaily 発火）</li>
 * </ul>
 *
 * <p><b>「明日に回す」実装注記</b>: 設計書 §5.4 に基づき {@code PATCH /api/v1/todos/{id}} で
 * {@code dueDate} を更新する。Backend エンドポイントは F02.3 で実装済み
 * （{@link PersonalTodoController#patchTodo}）。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()
const todoApi = useTodoApi()
const notification = useNotification()
const api = useApi()

// === 日付ユーティリティ（JST 基準）===
function todayJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

function tomorrowJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000 + 24 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

const today = ref(todayJst())

// === メモ一覧 ===
const todaysMemos = computed<ActionMemo[]>(() => store.currentDayMemos(today.value))

// === TODO 一覧 ===
interface TodoItem {
  id: number
  scopeType: string
  scopeId: number
  title: string
  status: string
  dueDate: string | null
}

const todos = ref<TodoItem[]>([])
const todosLoading = ref(false)

const completedTodos = computed(() =>
  todos.value.filter((t2) => t2.status === 'DONE' || t2.status === 'COMPLETED'),
)
const pendingTodos = computed(() =>
  todos.value.filter((t2) => t2.status !== 'DONE' && t2.status !== 'COMPLETED'),
)

async function loadTodos() {
  todosLoading.value = true
  try {
    const res = await todoApi.getMyTodos()
    // 当日 dueDate + PERSONAL スコープのみ
    const list = (res?.data ?? []) as Array<{
      id: number
      scopeType: string
      scopeId: number
      title: string
      status: string
      dueDate: string | null
    }>
    todos.value = list
      .filter((item) => item.scopeType === 'PERSONAL' && item.dueDate === today.value)
      .map((item) => ({
        id: item.id,
        scopeType: item.scopeType,
        scopeId: item.scopeId,
        title: item.title,
        status: item.status,
        dueDate: item.dueDate,
      }))
  } catch {
    todos.value = []
  } finally {
    todosLoading.value = false
  }
}

// === 明日やること入力 ===
const tomorrowInput = ref('')

async function addTomorrowTodo() {
  const title = tomorrowInput.value.trim()
  if (title.length === 0) return
  try {
    await todoApi.createPersonalTodo({
      title,
      dueDate: tomorrowJst(),
      priority: 'MEDIUM',
    })
    tomorrowInput.value = ''
    notification.success(t('action_memo.closing.tomorrow_added'))
  } catch {
    notification.error(t('action_memo.closing.tomorrow_add_failed'))
  }
}

// === 明日に回す ===
async function moveToTomorrow(todo: TodoItem) {
  try {
    // 設計書 §5.4 に基づき PATCH /api/v1/todos/{id} に dueDate 更新を送る
    await api(`/api/v1/todos/${todo.id}`, {
      method: 'PATCH',
      body: { dueDate: tomorrowJst() },
    })
    // 対象 TODO をローカルリストから取り除く（当日一覧なので表示対象外になる）
    todos.value = todos.value.filter((t2) => t2.id !== todo.id)
    notification.success(t('action_memo.closing.moved_to_tomorrow'))
  } catch {
    notification.error(t('action_memo.closing.move_failed'))
  }
}

// === Phase 3: チーム投稿 ===
const publishingToTeam = ref(false)

/** 今日の WORK メモが1件以上あるかチェック */
const hasWorkMemos = computed(() =>
  todaysMemos.value.some((m) => m.category === 'WORK'),
)

async function onPublishDailyToTeam() {
  if (publishingToTeam.value) return
  publishingToTeam.value = true
  try {
    const success = await store.publishDailyToTeam({
      teamId: store.settings.defaultPostTeamId ?? undefined,
    })
    if (success) {
      notification.success(t('action_memo.phase3.post_to_team.publish_daily_success'))
    } else {
      notification.error(t('action_memo.phase3.post_to_team.publish_daily_error'))
    }
  } catch {
    notification.error(t('action_memo.phase3.post_to_team.publish_daily_error'))
  } finally {
    publishingToTeam.value = false
  }
}

// === 終業投稿（publishDaily）===
const extraComment = ref('')
const publishing = ref(false)

async function onPublishDaily() {
  if (publishing.value) return
  publishing.value = true
  try {
    const response = await store.publishDaily({
      memoDate: today.value,
      extraComment: extraComment.value.trim() || undefined,
    })
    notification.success(
      t('action_memo.closing.publish_success', { count: response.memoCount }),
    )
    // ホーム相当（行動メモ画面）に戻す
    router.push('/action-memo')
  } catch {
    notification.error(t('action_memo.closing.publish_error'))
  } finally {
    publishing.value = false
  }
}

function goBack() {
  router.push('/action-memo')
}

onMounted(async () => {
  await Promise.all([
    store.fetchSettings(),
    store.fetchMemosForDate(today.value),
    store.fetchAvailableTeams(),
    loadTodos(),
  ])
})
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-5 px-3 py-4">
    <header class="flex items-center gap-2">
      <button
        type="button"
        class="rounded-lg px-2 py-1 text-sm text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-700"
        data-testid="action-memo-closing-back"
        @click="goBack"
      >
        <i class="pi pi-arrow-left mr-1 text-xs" />
        {{ t('action_memo.page.back_to_memo') }}
      </button>
    </header>

    <h1 class="text-xl font-bold" data-testid="action-memo-closing-title">
      {{ t('action_memo.closing.title') }}
    </h1>

    <div
      v-if="store.error"
      class="rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-200"
      role="alert"
    >
      {{ t(store.error) }}
    </div>

    <!-- 上部: 当日のメモ一覧 -->
    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.closing.today_memos') }}
      </h2>
      <ActionMemoList :memos="todaysMemos" :loading="store.loading" />
    </section>

    <!-- 中部: 完了 TODO -->
    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.closing.todos_completed') }}
      </h2>
      <ul
        v-if="completedTodos.length > 0"
        class="flex flex-col gap-1"
        data-testid="action-memo-closing-completed-todos"
      >
        <li
          v-for="todo in completedTodos"
          :key="todo.id"
          class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2 text-sm dark:border-surface-700 dark:bg-surface-800"
        >
          <i class="pi pi-check text-emerald-500" />
          <span class="flex-1 text-surface-700 line-through dark:text-surface-300">
            {{ todo.title }}
          </span>
        </li>
      </ul>
      <p
        v-else
        class="px-1 text-xs text-surface-400 dark:text-surface-500"
      >
        {{ t('action_memo.closing.no_completed_todos') }}
      </p>
    </section>

    <!-- 中部: 未完 TODO -->
    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.closing.todos_pending') }}
      </h2>
      <ul
        v-if="pendingTodos.length > 0"
        class="flex flex-col gap-1"
        data-testid="action-memo-closing-pending-todos"
      >
        <li
          v-for="todo in pendingTodos"
          :key="todo.id"
          class="flex items-center gap-2 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2 text-sm dark:border-surface-700 dark:bg-surface-800"
        >
          <i class="pi pi-circle text-surface-400" />
          <span class="flex-1 text-surface-800 dark:text-surface-100">
            {{ todo.title }}
          </span>
          <button
            type="button"
            class="rounded px-2 py-1 text-xs text-primary hover:bg-primary/10"
            :data-testid="`action-memo-closing-move-${todo.id}`"
            @click="moveToTomorrow(todo)"
          >
            {{ t('action_memo.closing.move_to_tomorrow') }}
          </button>
        </li>
      </ul>
      <p
        v-else
        class="px-1 text-xs text-surface-400 dark:text-surface-500"
      >
        {{ t('action_memo.closing.no_pending_todos') }}
      </p>
    </section>

    <!-- 下部: 明日やること入力 -->
    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.closing.tomorrow_todos') }}
      </h2>
      <div class="flex items-center gap-2">
        <input
          v-model="tomorrowInput"
          type="text"
          class="flex-1 rounded-lg border border-surface-200 bg-transparent px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
          :placeholder="t('action_memo.closing.tomorrow_input_placeholder')"
          data-testid="action-memo-closing-tomorrow-input"
          @keydown.enter="addTomorrowTodo"
        >
        <button
          type="button"
          class="rounded-lg bg-primary px-3 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="tomorrowInput.trim().length === 0"
          data-testid="action-memo-closing-tomorrow-submit"
          @click="addTomorrowTodo"
        >
          <i class="pi pi-plus text-xs" />
        </button>
      </div>
    </section>

    <!-- Phase 3: 今日のWORKメモをチーム投稿 -->
    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.phase3.post_to_team.publish_daily') }}
      </h2>
      <button
        type="button"
        class="w-full rounded-lg border border-primary px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="publishingToTeam || !hasWorkMemos"
        data-testid="action-memo-closing-publish-to-team"
        @click="onPublishDailyToTeam"
      >
        <i class="pi pi-users mr-2 text-xs" />
        {{ t('action_memo.phase3.post_to_team.publish_daily') }}
      </button>
      <p
        v-if="!hasWorkMemos"
        class="px-1 text-xs text-surface-400 dark:text-surface-500"
        data-testid="action-memo-closing-no-work-memos"
      >
        {{ t('action_memo.phase3.post_to_team.no_work_memos_hint') }}
      </p>
    </section>

    <!-- 下部: extra_comment + 今日を締める -->
    <section
      class="flex flex-col gap-2 rounded-2xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
    >
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.closing.extra_comment_label') }}
      </h2>
      <textarea
        v-model="extraComment"
        rows="3"
        class="w-full resize-y rounded-lg border border-surface-200 bg-transparent p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
        :placeholder="t('action_memo.closing.extra_comment_placeholder')"
        data-testid="action-memo-closing-extra-comment"
      />
      <button
        type="button"
        class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="publishing || todaysMemos.length === 0"
        data-testid="action-memo-closing-publish"
        @click="onPublishDaily"
      >
        <i class="pi pi-flag mr-2 text-xs" />
        {{ t('action_memo.closing.publish_button') }}
      </button>
      <p
        v-if="todaysMemos.length === 0"
        class="px-1 text-xs text-surface-400 dark:text-surface-500"
      >
        {{ t('action_memo.closing.no_memos_hint') }}
      </p>
    </section>
  </div>
</template>
