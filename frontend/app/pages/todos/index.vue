<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const router = useRouter()
const todoApi = useTodoApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()

// ===== 型 =====
interface MyTodo {
  id: number
  scopeType: string
  scopeId: number | null
  title: string
  description: string | null
  status: string
  priority: string
  dueDate: string | null
  daysRemaining: number | null
  assignees: Array<{ id: number; userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
}

// ===== データ =====
const todos = ref<MyTodo[]>([])
const loading = ref(true)
const viewMode = ref<'list' | 'kanban'>('list')
const scopeTab = ref<'all' | 'personal' | 'team' | 'organization'>('all')
const showCompleted = ref(false)
const showCreateDialog = ref(false)

// ===== スコープ名マップ（チームID/組織ID → 名前）=====
const teamNameMap = computed(() =>
  Object.fromEntries(teamStore.myTeams.map((t) => [t.id, t.nickname1 || t.name])),
)
const orgNameMap = computed(() =>
  Object.fromEntries(orgStore.myOrganizations.map((o) => [o.id, o.nickname1 || o.name])),
)

function scopeDisplayName(todo: MyTodo): string {
  if (todo.scopeType === 'PERSONAL') return '個人'
  if (todo.scopeType === 'TEAM' && todo.scopeId) return teamNameMap.value[todo.scopeId] ?? 'チーム'
  if (todo.scopeType === 'ORGANIZATION' && todo.scopeId)
    return orgNameMap.value[todo.scopeId] ?? '組織'
  return ''
}

function scopeColor(scopeType: string): string {
  if (scopeType === 'PERSONAL')
    return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
  if (scopeType === 'TEAM')
    return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
  return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400'
}

// ===== フィルタリング =====
const baseTodos = computed(() => {
  let list = todos.value
  if (scopeTab.value !== 'all') {
    list = list.filter((t) => t.scopeType === scopeTab.value.toUpperCase())
  }
  if (!showCompleted.value) {
    list = list.filter((t) => t.status !== 'COMPLETED')
  }
  return list
})

// ===== 進捗 =====
const progress = computed(() => {
  const base =
    scopeTab.value === 'all'
      ? todos.value
      : todos.value.filter((t) => t.scopeType === scopeTab.value.toUpperCase())
  const total = base.length
  const completed = base.filter((t) => t.status === 'COMPLETED').length
  return { total, completed, pct: total === 0 ? 0 : Math.round((completed / total) * 100) }
})

// ===== リストビュー用グループ =====
interface ListGroup {
  key: string
  label: string
  icon: string
  color: string
  todos: MyTodo[]
}

const listGroups = computed<ListGroup[]>(() => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const tomorrow = new Date(today)
  tomorrow.setDate(tomorrow.getDate() + 1)
  const nextWeek = new Date(today)
  nextWeek.setDate(nextWeek.getDate() + 7)

  const active = baseTodos.value.filter((t) => t.status !== 'COMPLETED')

  const overdue = active.filter((t) => t.dueDate && new Date(t.dueDate) < today)
  const todayItems = active.filter((t) => {
    if (!t.dueDate) return false
    const d = new Date(t.dueDate)
    return d >= today && d < tomorrow
  })
  const thisWeek = active.filter((t) => {
    if (!t.dueDate) return false
    const d = new Date(t.dueDate)
    return d >= tomorrow && d < nextWeek
  })
  const later = active.filter((t) => {
    if (!t.dueDate) return false
    return new Date(t.dueDate) >= nextWeek
  })
  const noDue = active.filter((t) => !t.dueDate)
  const completed = showCompleted.value
    ? baseTodos.value.filter((t) => t.status === 'COMPLETED')
    : []

  return [
    {
      key: 'overdue',
      label: '期限切れ',
      icon: 'pi pi-exclamation-circle',
      color: 'text-red-500',
      todos: overdue,
    },
    { key: 'today', label: '今日', icon: 'pi pi-sun', color: 'text-orange-500', todos: todayItems },
    { key: 'week', label: '今週', icon: 'pi pi-calendar', color: 'text-blue-500', todos: thisWeek },
    {
      key: 'later',
      label: 'それ以降',
      icon: 'pi pi-clock',
      color: 'text-surface-500',
      todos: later,
    },
    {
      key: 'nodue',
      label: '期限なし',
      icon: 'pi pi-minus',
      color: 'text-surface-400',
      todos: noDue,
    },
    {
      key: 'done',
      label: '完了済み',
      icon: 'pi pi-check-circle',
      color: 'text-green-500',
      todos: completed,
    },
  ].filter((g) => g.todos.length > 0)
})

// ===== カンバン用 =====
const kanbanCols = computed(() => [
  {
    status: 'OPEN',
    label: '未着手',
    color: 'bg-surface-100 dark:bg-surface-700',
    headerColor: 'text-surface-600 dark:text-surface-300',
    todos: baseTodos.value.filter((t) => t.status === 'OPEN'),
  },
  {
    status: 'IN_PROGRESS',
    label: '進行中',
    color: 'bg-blue-50 dark:bg-blue-900/10',
    headerColor: 'text-blue-600',
    todos: baseTodos.value.filter((t) => t.status === 'IN_PROGRESS'),
  },
  {
    status: 'COMPLETED',
    label: '完了',
    color: 'bg-green-50 dark:bg-green-900/10',
    headerColor: 'text-green-600',
    todos: baseTodos.value.filter((t) => t.status === 'COMPLETED'),
  },
])

// ===== ロード =====
async function load() {
  loading.value = true
  try {
    const [todosRes] = await Promise.all([
      todoApi.getMyTodos(),
      teamStore.myTeams.length === 0 ? teamStore.fetchMyTeams() : Promise.resolve(),
      orgStore.myOrganizations.length === 0 ? orgStore.fetchMyOrganizations() : Promise.resolve(),
    ])
    todos.value = todosRes.data
  } catch {
    todos.value = []
  } finally {
    loading.value = false
  }
}

// ===== ステータス変更 =====
async function changeStatus(todo: MyTodo, status: string) {
  try {
    await todoApi.changeTodoStatusById(todo.scopeType, todo.scopeId, todo.id, status)
    todo.status = status
  } catch {
    notification.error('ステータスの更新に失敗しました')
  }
}

function nextStatus(current: string): string {
  if (current === 'OPEN') return 'IN_PROGRESS'
  if (current === 'IN_PROGRESS') return 'COMPLETED'
  return 'OPEN'
}
function nextStatusLabel(current: string): string {
  if (current === 'OPEN') return '進行中にする'
  if (current === 'IN_PROGRESS') return '完了にする'
  return '未着手に戻す'
}

// ===== 優先度 =====
const priorityBorder: Record<string, string> = {
  HIGH: 'border-l-4 border-l-red-400',
  MEDIUM: 'border-l-4 border-l-yellow-400',
  LOW: 'border-l-4 border-l-green-400',
}
const priorityLabel: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const priorityClass: Record<string, string> = {
  HIGH: 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400',
  MEDIUM: 'bg-yellow-100 text-yellow-600 dark:bg-yellow-900/30 dark:text-yellow-400',
  LOW: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
}

// ===== 期限フォーマット =====
function formatDate(d: string | null): string {
  if (!d) return ''
  return new Date(d).toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' })
}
function isOverdue(todo: MyTodo): boolean {
  return !!todo.dueDate && (todo.daysRemaining ?? 0) < 0 && todo.status !== 'COMPLETED'
}

// ===== 作成ダイアログ =====
const createForm = ref({
  title: '',
  description: '',
  priority: 'MEDIUM' as string,
  dueDate: null as Date | null,
  scopeType: 'PERSONAL' as string,
  scopeId: null as number | null,
})
const creating = ref(false)

const scopeOptions = computed(() => {
  const opts: Array<{ label: string; scopeType: string; scopeId: number | null }> = [
    { label: '個人', scopeType: 'PERSONAL', scopeId: null },
  ]
  teamStore.myTeams.forEach((t) =>
    opts.push({ label: t.nickname1 || t.name, scopeType: 'TEAM', scopeId: t.id }),
  )
  orgStore.myOrganizations.forEach((o) =>
    opts.push({ label: o.nickname1 || o.name, scopeType: 'ORGANIZATION', scopeId: o.id }),
  )
  return opts
})

const selectedScopeOption = computed({
  get: () =>
    scopeOptions.value.find(
      (o) => o.scopeType === createForm.value.scopeType && o.scopeId === createForm.value.scopeId,
    ) ?? scopeOptions.value[0]!,
  set: (val) => {
    createForm.value.scopeType = val.scopeType
    createForm.value.scopeId = val.scopeId
  },
})

function resetForm() {
  createForm.value = {
    title: '',
    description: '',
    priority: 'MEDIUM',
    dueDate: null,
    scopeType: 'PERSONAL',
    scopeId: null,
  }
}

async function submitCreate() {
  if (!createForm.value.title.trim()) return
  creating.value = true
  let success = false
  try {
    const body = {
      title: createForm.value.title.trim(),
      description: createForm.value.description.trim() || undefined,
      priority: createForm.value.priority,
      dueDate: createForm.value.dueDate
        ? createForm.value.dueDate.toISOString().slice(0, 10)
        : undefined,
    }
    if (createForm.value.scopeType === 'PERSONAL') {
      await todoApi.createPersonalTodo(body)
    } else {
      const type =
        createForm.value.scopeType === 'TEAM' ? ('team' as const) : ('organization' as const)
      await todoApi.createTodo(type, createForm.value.scopeId!, body)
    }
    success = true
  } catch {
    notification.error('作成に失敗しました')
  } finally {
    creating.value = false
  }

  if (success) {
    showCreateDialog.value = false
    notification.success('TODOを作成しました')
    await nextTick()
    await load()
  }
}

onMounted(load)
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
        <h1 class="text-2xl font-bold">マイTODO</h1>
      </div>
      <div class="flex items-center gap-2">
        <Button
          :icon="showCompleted ? 'pi pi-eye-slash' : 'pi pi-eye'"
          :label="showCompleted ? '完了を隠す' : '完了を表示'"
          text
          size="small"
          severity="secondary"
          @click="showCompleted = !showCompleted"
        />
        <SelectButton
          v-model="viewMode"
          :options="[
            { value: 'list', icon: 'pi pi-list' },
            { value: 'kanban', icon: 'pi pi-th-large' },
          ]"
          option-value="value"
          option-label="value"
        >
          <template #option="{ option }">
            <i :class="option.icon" />
          </template>
        </SelectButton>
        <Button label="作成" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

    <!-- 進捗バー -->
    <div
      class="mb-5 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
    >
      <div class="mb-2 flex items-center justify-between text-sm">
        <span class="font-medium text-surface-600 dark:text-surface-300">
          完了 <span class="font-bold text-primary">{{ progress.completed }}</span> /
          {{ progress.total }}件
        </span>
        <span class="font-bold text-primary">{{ progress.pct }}%</span>
      </div>
      <ProgressBar :value="progress.pct" :show-value="false" style="height: 8px" />
    </div>

    <!-- スコープタブ -->
    <div class="mb-5 flex flex-wrap gap-2">
      <button
        v-for="tab in [
          { key: 'all', label: 'すべて' },
          { key: 'personal', label: '個人' },
          { key: 'team', label: 'チーム' },
          { key: 'organization', label: '組織' },
        ]"
        :key="tab.key"
        class="rounded-full px-4 py-1.5 text-sm font-medium transition-colors"
        :class="
          scopeTab === tab.key
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-700 dark:text-surface-300'
        "
        @click="scopeTab = tab.key as typeof scopeTab"
      >
        {{ tab.label }}
        <span class="ml-1 text-xs opacity-70">
          {{
            tab.key === 'all'
              ? todos.filter((t) => t.status !== 'COMPLETED').length
              : todos.filter(
                  (t) => t.scopeType === tab.key.toUpperCase() && t.status !== 'COMPLETED',
                ).length
          }}
        </span>
      </button>
    </div>

    <PageLoading v-if="loading" />

    <!-- ===== リストビュー ===== -->
    <template v-else-if="viewMode === 'list'">
      <div v-if="listGroups.length === 0" class="py-16 text-center text-surface-400">
        <i class="pi pi-check-circle mb-3 text-4xl text-green-400" />
        <p>TODOはすべて完了しています</p>
      </div>

      <div v-for="group in listGroups" :key="group.key" class="mb-6">
        <!-- グループヘッダー -->
        <div class="mb-2 flex items-center gap-2">
          <i :class="[group.icon, group.color, 'text-sm']" />
          <span :class="[group.color, 'text-sm font-semibold']">{{ group.label }}</span>
          <span
            class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700"
          >
            {{ group.todos.length }}
          </span>
        </div>

        <!-- TODO行 -->
        <div class="space-y-2">
          <div
            v-for="todo in group.todos"
            :key="todo.id"
            class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 px-4 py-3 transition-shadow hover:shadow-sm dark:border-surface-700 dark:bg-surface-800"
            :class="priorityBorder[todo.priority]"
          >
            <!-- チェックボックス (完了トグル) -->
            <Checkbox
              :model-value="todo.status === 'COMPLETED'"
              binary
              @update:model-value="
                changeStatus(todo, todo.status === 'COMPLETED' ? 'OPEN' : 'COMPLETED')
              "
            />

            <!-- コンテンツ -->
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <p
                  class="text-sm font-medium"
                  :class="
                    todo.status === 'COMPLETED'
                      ? 'text-surface-400 line-through'
                      : 'text-surface-800 dark:text-surface-100'
                  "
                >
                  {{ todo.title }}
                </p>
                <!-- スコープバッジ -->
                <span
                  class="rounded-full px-2 py-0.5 text-[11px] font-medium"
                  :class="scopeColor(todo.scopeType)"
                >
                  {{ scopeDisplayName(todo) }}
                </span>
              </div>
              <div class="mt-1 flex items-center gap-3">
                <span
                  v-if="todo.dueDate"
                  class="text-xs"
                  :class="isOverdue(todo) ? 'font-semibold text-red-500' : 'text-surface-400'"
                >
                  <i class="pi pi-calendar mr-0.5" />{{ formatDate(todo.dueDate) }}
                  <span v-if="isOverdue(todo)">（期限切れ）</span>
                </span>
                <span
                  v-if="todo.assignees.length > 0"
                  class="flex items-center gap-1 text-xs text-surface-400"
                >
                  <i class="pi pi-user" />
                  {{ todo.assignees.map((a) => a.displayName).join(', ') }}
                </span>
              </div>
            </div>

            <!-- 優先度バッジ -->
            <span
              class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
              :class="priorityClass[todo.priority]"
            >
              {{ priorityLabel[todo.priority] }}
            </span>

            <!-- ステータス変更 -->
            <Button
              v-if="todo.status !== 'COMPLETED'"
              :label="nextStatusLabel(todo.status)"
              size="small"
              text
              severity="secondary"
              class="shrink-0 !text-xs"
              @click="changeStatus(todo, nextStatus(todo.status))"
            />
          </div>
        </div>
      </div>
    </template>

    <!-- ===== カンバンビュー ===== -->
    <template v-else>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
        <div
          v-for="col in kanbanCols"
          :key="col.status"
          class="rounded-xl border border-surface-200 dark:border-surface-700"
        >
          <!-- 列ヘッダー -->
          <div class="flex items-center justify-between rounded-t-xl px-4 py-3" :class="col.color">
            <span class="font-semibold" :class="col.headerColor">{{ col.label }}</span>
            <span
              class="rounded-full bg-white/60 px-2 py-0.5 text-xs font-bold dark:bg-black/20"
              :class="col.headerColor"
            >
              {{ col.todos.length }}
            </span>
          </div>

          <!-- カード群 -->
          <div class="space-y-2 p-3">
            <div
              v-for="todo in col.todos"
              :key="todo.id"
              class="rounded-lg border border-surface-200 bg-surface-0 p-3 shadow-sm dark:border-surface-600 dark:bg-surface-800"
              :class="priorityBorder[todo.priority]"
            >
              <!-- タイトル -->
              <p
                class="mb-2 text-sm font-medium leading-snug text-surface-800 dark:text-surface-100"
                :class="{ 'line-through text-surface-400': todo.status === 'COMPLETED' }"
              >
                {{ todo.title }}
              </p>

              <!-- メタ情報 -->
              <div class="flex flex-wrap items-center gap-1.5">
                <span
                  class="rounded-full px-1.5 py-0.5 text-[10px] font-medium"
                  :class="scopeColor(todo.scopeType)"
                >
                  {{ scopeDisplayName(todo) }}
                </span>
                <span
                  class="rounded-full px-1.5 py-0.5 text-[10px] font-semibold"
                  :class="priorityClass[todo.priority]"
                >
                  {{ priorityLabel[todo.priority] }}
                </span>
                <span
                  v-if="todo.dueDate"
                  class="flex items-center gap-0.5 text-[10px]"
                  :class="isOverdue(todo) ? 'text-red-500 font-semibold' : 'text-surface-400'"
                >
                  <i class="pi pi-calendar" />{{ formatDate(todo.dueDate) }}
                </span>
              </div>

              <!-- 担当者 -->
              <div v-if="todo.assignees.length > 0" class="mt-2 flex -space-x-1">
                <Avatar
                  v-for="a in todo.assignees.slice(0, 4)"
                  :key="a.userId"
                  v-tooltip="a.displayName"
                  :image="a.avatarUrl ?? undefined"
                  :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
                  size="small"
                  shape="circle"
                  class="border-2 border-surface-0 dark:border-surface-800"
                />
              </div>

              <!-- ステータス移動ボタン -->
              <div class="mt-2 flex gap-1">
                <Button
                  v-if="col.status !== 'OPEN'"
                  v-tooltip="col.status === 'IN_PROGRESS' ? '未着手に戻す' : '進行中に戻す'"
                  icon="pi pi-arrow-left"
                  size="small"
                  text
                  severity="secondary"
                  class="!p-1"
                  @click="changeStatus(todo, col.status === 'IN_PROGRESS' ? 'OPEN' : 'IN_PROGRESS')"
                />
                <Button
                  v-if="col.status !== 'COMPLETED'"
                  v-tooltip="col.status === 'OPEN' ? '進行中にする' : '完了にする'"
                  icon="pi pi-arrow-right"
                  size="small"
                  text
                  severity="secondary"
                  class="!p-1 ml-auto"
                  @click="changeStatus(todo, col.status === 'OPEN' ? 'IN_PROGRESS' : 'COMPLETED')"
                />
              </div>
            </div>

            <!-- 空状態 -->
            <div v-if="col.todos.length === 0" class="py-6 text-center text-xs text-surface-400">
              なし
            </div>

            <!-- 作成ショートカット (未着手列のみ) -->
            <button
              v-if="col.status === 'OPEN'"
              class="flex w-full items-center gap-2 rounded-lg border border-dashed border-surface-300 px-3 py-2 text-sm text-surface-400 transition-colors hover:border-primary hover:text-primary dark:border-surface-600"
              @click="showCreateDialog = true"
            >
              <i class="pi pi-plus" />追加する
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- ===== 作成ダイアログ ===== -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="TODOを作成"
      modal
      class="w-full max-w-lg"
      @hide="resetForm"
    >
      <div class="space-y-4">
        <!-- タイトル -->
        <div>
          <label class="mb-1 block text-sm font-medium"
            >タイトル <span class="text-red-500">*</span></label
          >
          <InputText
            v-model="createForm.title"
            class="w-full"
            placeholder="TODOのタイトル"
            autofocus
          />
        </div>

        <!-- 説明 -->
        <div>
          <label class="mb-1 block text-sm font-medium">説明（任意）</label>
          <Textarea
            v-model="createForm.description"
            class="w-full"
            rows="2"
            placeholder="詳細や補足"
            auto-resize
          />
        </div>

        <!-- スコープ + 優先度 -->
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">スコープ</label>
            <Select
              v-model="selectedScopeOption"
              :options="scopeOptions"
              option-label="label"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">優先度</label>
            <Select
              v-model="createForm.priority"
              :options="[
                { label: '高', value: 'HIGH' },
                { label: '中', value: 'MEDIUM' },
                { label: '低', value: 'LOW' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>

        <!-- 期限 -->
        <div>
          <label class="mb-1 block text-sm font-medium">期限（任意）</label>
          <DatePicker
            v-model="createForm.dueDate"
            class="w-full"
            date-format="yy/mm/dd"
            show-icon
          />
        </div>
      </div>

      <template #footer>
        <Button label="キャンセル" text severity="secondary" @click="showCreateDialog = false" />
        <Button
          label="作成"
          icon="pi pi-check"
          :loading="creating"
          :disabled="!createForm.title.trim()"
          @click="submitCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
