<script setup lang="ts">
const props = defineProps<{
  scopeType: 'personal' | 'team' | 'organization'
  scopeId?: number
  scopeName?: string
  scopeTemplate?: string
}>()

const { sortedWidgets, visibleWidgets, isVisible, toggleWidget, reorder } = useDashboardWidgets(
  props.scopeType,
  props.scopeId,
)

const showConfig = ref(false)
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)
const collapsedKeys = ref<Set<string>>(new Set())
// 初期表示時に localStorage の保存順が適用される際にアニメーションしないよう、
// mount + nextTick 後にのみ move-class を有効にする
const isReady = ref(false)
onMounted(() => {
  nextTick(() => {
    isReady.value = true
  })
})

function toggleCollapse(key: string) {
  if (collapsedKeys.value.has(key)) {
    collapsedKeys.value.delete(key)
  } else {
    collapsedKeys.value.add(key)
  }
  // trigger reactivity
  collapsedKeys.value = new Set(collapsedKeys.value)
}

const DATA_WIDGET_KEYS = new Set(['survey-results', 'attendance-results', 'recruitment-feed', 'my-recruitments'])

function isDataWidget(key: string): boolean {
  return DATA_WIDGET_KEYS.has(key)
}

const basePath = computed(() => {
  if (props.scopeType === 'personal' || !props.scopeId) return undefined
  return props.scopeType === 'team' ? `/teams/${props.scopeId}` : `/organizations/${props.scopeId}`
})

function linkTo(widgetKey: string): string | undefined {
  if (props.scopeType === 'personal') {
    const personalLinks: Record<string, string> = {
      'upcoming-events': '/calendar',
      todos: '/todos',
      timeline: '/timeline',
      chat: '/chat',
      notifications: '/notifications',
      blog: '/my/blog',
      'recruitment-feed': '/me/recruitment-feed',
      'my-recruitments': '/me/recruitment-listings',
    }
    return personalLinks[widgetKey]
  }
  const base = basePath.value!
  const scopeLinks: Record<string, string> = {
    'upcoming-events': `${base}/schedule`,
    todos: `${base}/todos`,
    timeline: `${base}/timeline`,
    bulletin: `${base}/bulletin`,
    blog: `${base}/blog`,
    chat: `${base}/chat`,
    schedule: `${base}/schedule`,
    members: `${base}/member-profiles`,
    activities: `${base}/activities`,
    gallery: `${base}/gallery`,
    circulation: `${base}/circulation`,
    surveys: `${base}/surveys`,
    'survey-results': `${base}/surveys`,
    'attendance-results': `${base}/schedule`,
  }
  return scopeLinks[widgetKey]
}

function onDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  dropTargetIndex.value = index
}

function onDragLeave(e: DragEvent) {
  // 子要素への移動時は無視
  const target = e.currentTarget as HTMLElement
  if (target.contains(e.relatedTarget as Node)) return
  dropTargetIndex.value = null
}

function onDrop(index: number) {
  if (dragIndex.value !== null && dragIndex.value !== index) {
    reorder(dragIndex.value, index)
  }
  dragIndex.value = null
  dropTargetIndex.value = null
}

function onDragEnd() {
  dragIndex.value = null
  dropTargetIndex.value = null
}
</script>

<template>
  <div class="space-y-6">
    <!-- ウィジェット設定ボタン -->
    <div class="flex justify-end">
      <Button
        label="ウィジェット設定"
        icon="pi pi-cog"
        text
        size="small"
        @click="showConfig = true"
      />
    </div>

    <!-- ウィジェットグリッド -->
    <TransitionGroup
      tag="div"
      class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
      :move-class="isReady ? 'transition-all duration-[350ms] ease-in-out' : ''"
    >
      <!-- 空状態 -->
      <div
        v-if="visibleWidgets.length === 0"
        key="empty-state"
        class="col-span-full rounded-xl border border-dashed border-surface-400 py-12 text-center dark:border-surface-600"
      >
        <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">表示するウィジェットがありません</p>
        <Button
          label="ウィジェットを追加"
          icon="pi pi-plus"
          text
          size="small"
          class="mt-2"
          @click="showConfig = true"
        />
      </div>

      <DashboardWidgetCard
        v-for="(w, index) in visibleWidgets"
        :key="w.key"
        title=""
        class="group cursor-default transition-all"
        :col-span="isDataWidget(w.key) ? 2 : 1"
        :scrollable="false"
        :is-dragging="dragIndex === index"
        :is-drop-target="dropTargetIndex === index && dragIndex !== index"
        draggable="true"
        @dragstart="onDragStart(index, $event)"
        @dragover="onDragOver(index, $event)"
        @dragleave="onDragLeave($event)"
        @drop.prevent="onDrop(index)"
        @dragend="onDragEnd"
        @click="!isDataWidget(w.key) && dragIndex === null && navigateTo(linkTo(w.key) ?? '#')"
      >
        <!-- ドラッグハンドル（hover時に表示） -->
        <i
          class="pi pi-grip-vertical absolute right-3 top-3 cursor-grab text-sm text-surface-300 opacity-0 transition-opacity group-hover:opacity-100 active:cursor-grabbing dark:text-surface-600"
        />

        <div class="flex items-center gap-3" :class="collapsedKeys.has(w.key) || isDataWidget(w.key) ? '' : 'mb-3'">
          <div
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary/20"
          >
            <i :class="w.icon" class="text-xl" />
          </div>
          <NuxtLink
            v-if="linkTo(w.key)"
            :to="linkTo(w.key)"
            class="group/title flex-1"
            @click.stop
          >
            <h3
              class="text-[20px] font-semibold text-surface-700 transition-colors group-hover/title:text-primary dark:text-surface-200"
            >
              {{ w.label }}
            </h3>
          </NuxtLink>
          <h3
            v-else
            class="flex-1 text-[20px] font-semibold text-surface-700 dark:text-surface-200"
          >
            {{ w.label }}
          </h3>
          <!-- 折り畳みボタン (モバイルのみ・ナビゲーションウィジェットのみ) -->
          <button
            v-if="!isDataWidget(w.key)"
            class="md:hidden flex items-center justify-center rounded-lg p-1.5 text-surface-400 transition-colors hover:bg-surface-100"
            @click.stop="toggleCollapse(w.key)"
          >
            <i
              class="pi text-sm transition-transform duration-200"
              :class="collapsedKeys.has(w.key) ? 'pi-chevron-down' : 'pi-chevron-up'"
            />
          </button>
          <!-- ナビゲーション矢印 (ナビゲーションウィジェットのみ) -->
          <i
            v-if="!isDataWidget(w.key)"
            class="pi pi-chevron-right hidden md:block text-xs text-surface-400 opacity-0 transition-opacity group-hover:opacity-100"
          />
          <!-- データウィジェット: ページリンク -->
          <NuxtLink
            v-else
            :to="linkTo(w.key)"
            class="shrink-0 text-xs text-surface-400 hover:text-primary"
            @click.stop
          >
            詳細 <i class="pi pi-external-link text-[10px]" />
          </NuxtLink>
        </div>

        <!-- ナビゲーションウィジェット: 説明文 -->
        <p
          v-if="!isDataWidget(w.key)"
          class="text-xs text-surface-500"
          :class="collapsedKeys.has(w.key) ? 'hidden md:block' : ''"
        >
          {{ w.description }}
        </p>

        <!-- データウィジェット: 実コンテンツ -->
        <template v-if="isDataWidget(w.key)">
          <div class="mt-3 max-h-96 overflow-y-auto pr-1">
            <WidgetSurveyResults
              v-if="w.key === 'survey-results' && scopeId"
              :scope-type="(scopeType as 'team' | 'organization')"
              :scope-id="scopeId"
            />
            <WidgetAttendanceResults
              v-else-if="w.key === 'attendance-results' && scopeId"
              :scope-type="(scopeType as 'team' | 'organization')"
              :scope-id="scopeId"
            />
            <!-- Phase 2: F03.11 募集型予約ウィジェット -->
            <WidgetRecruitmentFeed v-else-if="w.key === 'recruitment-feed'" />
            <WidgetMyRecruitments v-else-if="w.key === 'my-recruitments'" />
          </div>
        </template>
      </DashboardWidgetCard>

      <!-- Amazon広告タイル (非表示不可・常に最後) -->
      <WidgetAmazonAd
        key="amazon-ad"
        class="order-last"
        :scope-type="scopeType"
        :scope-template="scopeTemplate"
      />
      <!-- 楽天広告タイル (非表示不可・常に最後) -->
      <WidgetRakutenAd
        key="rakuten-ad"
        class="order-last"
        :scope-type="scopeType"
        :scope-template="scopeTemplate"
      />
    </TransitionGroup>

    <!-- 設定ダイアログ -->
    <DashboardConfigDialog
      v-model:visible="showConfig"
      :widgets="sortedWidgets"
      :is-visible="isVisible"
      @toggle="toggleWidget"
      @reorder="reorder"
    />
  </div>
</template>
