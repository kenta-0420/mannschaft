<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const {
  todos,
  loading,
  scopeTab,
  showCompleted,
  progress,
  listGroups,
  kanbanCols,
  load,
  changeStatus,
  deleteTodo,
  scopeDisplayName,
  scopeColor,
  formatDate,
  isOverdue,
} = useTodoList()

const viewMode = ref<'list' | 'kanban' | 'gantt'>('list')
const showCreateDialog = ref(false)

// ガントビュー（ページ内表示・別ページ遷移なし）
const {
  currentYear: ganttYear,
  currentMonth: ganttMonth,
  scopeKey: ganttScopeKey,
  scopeOptions: ganttScopeOptions,
  ganttTodos,
  ganttFromDate,
  ganttToDate,
  ganttLoading,
  ganttKey,
  loadGantt: loadGanttView,
  onPrevMonth: ganttPrevMonth,
  onNextMonth: ganttNextMonth,
  ensureScopes: ensureGanttScopes,
} = useTodoGanttView()

// ガントモードへ切り替わったら初回ロード（キャッシュヒット時は即表示）
watch(viewMode, async (m) => {
  if (m === 'gantt') {
    await ensureGanttScopes()
    loadGanttView()
  }
})

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
          v-if="viewMode !== 'gantt'"
          :icon="showCompleted ? 'pi pi-eye-slash' : 'pi pi-eye'"
          :label="showCompleted ? '完了を隠す' : '完了を表示'"
          text
          size="small"
          severity="secondary"
          @click="showCompleted = !showCompleted"
        />
        <Select
          v-if="viewMode === 'gantt' && ganttScopeOptions.length > 1"
          v-model="ganttScopeKey"
          :options="ganttScopeOptions"
          option-label="label"
          option-value="value"
          size="small"
        />
        <div class="flex gap-1 rounded-lg border border-surface-300 bg-surface-100 p-1 dark:border-surface-600 dark:bg-surface-700">
          <button
            v-for="opt in [
              { value: 'list', icon: 'pi pi-list', tooltip: t('todo.list.viewModeList') },
              { value: 'kanban', icon: 'pi pi-th-large', tooltip: t('todo.list.viewModeKanban') },
              { value: 'gantt', icon: 'pi pi-chart-bar', tooltip: t('todo.enhancement.gantt.title') },
            ]"
            :key="opt.value"
            v-tooltip.bottom="opt.tooltip"
            type="button"
            class="rounded-md px-3 py-1.5 text-sm transition-colors"
            :class="viewMode === opt.value
              ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
            @click="viewMode = opt.value as typeof viewMode"
          >
            <i :class="opt.icon" />
          </button>
        </div>
        <Button label="作成" icon="pi pi-plus" @click="showCreateDialog = true" />
      </div>
    </div>

    <!-- 進捗バー（一覧・カンバン用） -->
    <DashboardWidgetCard v-if="viewMode !== 'gantt'" :scrollable="false" class="mb-5">
      <div class="flex flex-col gap-2">
        <div class="flex items-center justify-between text-sm">
          <span class="font-medium text-surface-600 dark:text-surface-300">
            完了 <span class="font-bold text-primary">{{ progress.completed }}</span> /
            {{ progress.total }}件
          </span>
          <span class="font-bold text-primary">{{ progress.pct }}%</span>
        </div>
        <ProgressBar :value="progress.pct" :show-value="false" style="height: 8px" />
      </div>
    </DashboardWidgetCard>

    <!-- スコープタブ（一覧・カンバン用） -->
    <div v-if="viewMode !== 'gantt'" class="mb-5 flex gap-1 rounded-lg border border-surface-300 bg-surface-100 p-1 w-fit dark:border-surface-600 dark:bg-surface-700">
      <button
        v-for="tab in [
          { key: 'all', label: 'すべて' },
          { key: 'personal', label: '個人' },
          { key: 'team', label: 'チーム' },
          { key: 'organization', label: '組織' },
        ]"
        :key="tab.key"
        class="rounded-md px-4 py-1.5 text-sm font-medium transition-colors"
        :class="
          scopeTab === tab.key
            ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'
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

    <!-- ガントビュー（ページ内表示） -->
    <DashboardWidgetCard v-if="viewMode === 'gantt'" :scrollable="false">
      <div v-if="ganttLoading" class="space-y-3">
        <Skeleton v-for="i in 5" :key="i" height="2rem" />
      </div>
      <Transition v-else name="fade">
        <TodoGanttView
          :key="ganttKey"
          :todos="ganttTodos"
          :from-date="ganttFromDate"
          :to-date="ganttToDate"
          :current-year="ganttYear"
          :current-month="ganttMonth"
          @todo-click="(id) => router.push(`/todos/${id}`)"
          @prev-month="ganttPrevMonth"
          @next-month="ganttNextMonth"
        />
      </Transition>
    </DashboardWidgetCard>

    <PageLoading v-else-if="loading" />

    <TodoListView
      v-else-if="viewMode === 'list'"
      :list-groups="listGroups"
      :scope-display-name="scopeDisplayName"
      :scope-color="scopeColor"
      :format-date="formatDate"
      :is-overdue="isOverdue"
      @change-status="changeStatus"
      @delete-todo="deleteTodo"
    />

    <TodoKanbanView
      v-else
      :kanban-cols="kanbanCols"
      :scope-display-name="scopeDisplayName"
      :scope-color="scopeColor"
      :format-date="formatDate"
      :is-overdue="isOverdue"
      @change-status="changeStatus"
      @create="showCreateDialog = true"
    />

    <TodoCreateDialog v-model:visible="showCreateDialog" @created="load" />
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
