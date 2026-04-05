<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const router = useRouter()
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
  nextStatus,
  nextStatusLabel,
  scopeDisplayName,
  scopeColor,
  formatDate,
  isOverdue,
} = useTodoList()

const viewMode = ref<'list' | 'kanban'>('list')
const showCreateDialog = ref(false)

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
      class="mb-5 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
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

    <TodoListView
      v-else-if="viewMode === 'list'"
      :list-groups="listGroups"
      :scope-display-name="scopeDisplayName"
      :scope-color="scopeColor"
      :format-date="formatDate"
      :is-overdue="isOverdue"
      :next-status="nextStatus"
      :next-status-label="nextStatusLabel"
      @change-status="changeStatus"
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
