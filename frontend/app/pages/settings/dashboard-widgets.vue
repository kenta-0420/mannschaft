<script setup lang="ts">
import { useDashboardWidgets } from '~/composables/useDashboardWidgets'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()

// ページタイトルを設定
useHead({
  title: t('dashboard.widget_settings.settings_page_title'),
})

// スコープ選択状態
type ScopeTab = 'personal' | 'team' | 'organization'
const activeScope = ref<ScopeTab>('personal')

// 所属チーム・組織ストア
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()

// チーム・組織の一覧取得（マウント時に一度だけ取得）
onMounted(async () => {
  if (teamStore.myTeams.length === 0) {
    await teamStore.fetchMyTeams()
  }
  if (orgStore.myOrganizations.length === 0) {
    await orgStore.fetchMyOrganizations()
  }
})

// 選択中チーム・組織のscopeId
const selectedTeamId = ref<string | null>(null)
const selectedOrgId = ref<string | null>(null)

// チーム一覧（数値idを文字列として扱う）
const teamOptions = computed(() =>
  teamStore.myTeams.map((t) => ({ label: t.name, value: String(t.id) })),
)
// 組織一覧
const orgOptions = computed(() =>
  orgStore.myOrganizations.map((o) => ({ label: o.name, value: String(o.id) })),
)

// スコープ切替時にデフォルト選択を初期化
watch(activeScope, (scope) => {
  if (scope === 'team' && !selectedTeamId.value && teamOptions.value.length > 0) {
    selectedTeamId.value = teamOptions.value[0]?.value ?? null
  }
  if (scope === 'organization' && !selectedOrgId.value && orgOptions.value.length > 0) {
    selectedOrgId.value = orgOptions.value[0]?.value ?? null
  }
})

// チーム・組織一覧が取得できたらデフォルト選択
watch(teamOptions, (opts) => {
  if (!selectedTeamId.value && opts.length > 0) {
    selectedTeamId.value = opts[0]?.value ?? null
  }
})
watch(orgOptions, (opts) => {
  if (!selectedOrgId.value && opts.length > 0) {
    selectedOrgId.value = opts[0]?.value ?? null
  }
})

// 現在のスコープIDを算出
const currentScopeId = computed<string | undefined>(() => {
  if (activeScope.value === 'team') return selectedTeamId.value ?? undefined
  if (activeScope.value === 'organization') return selectedOrgId.value ?? undefined
  return undefined
})

// useDashboardWidgets に渡す viewerRole（設定ページではすべて表示するため ADMIN とする）
const viewerRole = 'ADMIN' as const

// useDashboardWidgets を scopeType / scopeId 変化に合わせて再生成
// computedReactive パターン: activeScope や currentScopeId が変わるたびに再呼び出し
const widgets = computed(() =>
  useDashboardWidgets(activeScope.value, currentScopeId.value, viewerRole),
)

// ドラッグ&ドロップ状態
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)

function onDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = 'move'
  }
  dropTargetIndex.value = index
}

function onDragLeave() {
  dropTargetIndex.value = null
}

function onDrop(index: number) {
  if (dragIndex.value !== null && dragIndex.value !== index) {
    widgets.value.reorder(dragIndex.value, index)
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
  <div class="mx-auto max-w-2xl">
    <!-- PageHeader: 戻るボタンは内蔵 back-to で処理（手動BackButton併置禁止） -->
    <PageHeader
      :title="$t('dashboard.widget_settings.settings_page_title')"
      back-to="/settings"
    />

    <p class="mb-6 text-sm text-surface-500">
      {{ $t('dashboard.widget_settings.settings_page_description') }}
    </p>

    <!-- スコープ選択タブ -->
    <div class="mb-4 flex gap-2">
      <Button
        :label="$t('dashboard.widget_settings.scope_personal')"
        :outlined="activeScope !== 'personal'"
        :severity="activeScope === 'personal' ? 'primary' : 'secondary'"
        size="small"
        @click="activeScope = 'personal'"
      />
      <Button
        :label="$t('dashboard.widget_settings.scope_team')"
        :outlined="activeScope !== 'team'"
        :severity="activeScope === 'team' ? 'primary' : 'secondary'"
        size="small"
        @click="activeScope = 'team'"
      />
      <Button
        :label="$t('dashboard.widget_settings.scope_organization')"
        :outlined="activeScope !== 'organization'"
        :severity="activeScope === 'organization' ? 'primary' : 'secondary'"
        size="small"
        @click="activeScope = 'organization'"
      />
    </div>

    <!-- チーム選択 -->
    <div v-if="activeScope === 'team'" class="mb-4">
      <div v-if="teamOptions.length === 0" class="rounded-lg border border-surface-200 p-4 text-center text-sm text-surface-400 dark:border-surface-600">
        {{ $t('dashboard.widget_settings.no_teams') }}
      </div>
      <Select
        v-else
        v-model="selectedTeamId"
        :options="teamOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('dashboard.widget_settings.select_team')"
        class="w-full"
      />
    </div>

    <!-- 組織選択 -->
    <div v-if="activeScope === 'organization'" class="mb-4">
      <div v-if="orgOptions.length === 0" class="rounded-lg border border-surface-200 p-4 text-center text-sm text-surface-400 dark:border-surface-600">
        {{ $t('dashboard.widget_settings.no_organizations') }}
      </div>
      <Select
        v-else
        v-model="selectedOrgId"
        :options="orgOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('dashboard.widget_settings.select_organization')"
        class="w-full"
      />
    </div>

    <!-- ウィジェット一覧（ドラッグ並び替え + トグル） -->
    <template v-if="activeScope !== 'team' || selectedTeamId">
      <template v-if="activeScope !== 'organization' || selectedOrgId">
        <p class="mb-3 text-sm text-surface-500">
          {{ $t('dashboard.widget_settings.drag_hint') }}
        </p>

        <div
          v-if="widgets.sortedWidgets.value.length === 0"
          class="rounded-lg border border-surface-200 p-8 text-center text-sm text-surface-400 dark:border-surface-600"
        >
          {{ $t('dashboard.widget_settings.no_widgets_message') }}
        </div>

        <div v-else class="space-y-1">
          <div
            v-for="(w, index) in widgets.sortedWidgets.value"
            :key="w.key"
            draggable="true"
            class="flex cursor-grab items-center gap-2 rounded-lg border p-3 transition-colors active:cursor-grabbing"
            :class="[
              dragIndex === index
                ? 'border-primary/40 bg-primary/5 opacity-50'
                : dropTargetIndex === index
                  ? 'border-primary bg-primary/10'
                  : 'border-surface-200 dark:border-surface-600',
            ]"
            @dragstart="onDragStart(index, $event)"
            @dragover="onDragOver(index, $event)"
            @dragleave="onDragLeave"
            @drop="onDrop(index)"
            @dragend="onDragEnd"
          >
            <i class="pi pi-bars text-sm text-surface-400" />
            <i :class="w.icon" class="text-lg text-primary" />
            <div class="min-w-0 flex-1">
              <p class="text-sm font-medium">{{ w.label }}</p>
              <p class="text-xs text-surface-500">{{ w.description }}</p>
            </div>
            <ToggleSwitch
              :model-value="widgets.isVisible(w.key)"
              @update:model-value="widgets.toggleWidget(w.key)"
            />
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
