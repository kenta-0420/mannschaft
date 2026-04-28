<script setup lang="ts">
import {
  WidgetDefaultMinRoleMap,
  backendKeyForWidget,
  type WidgetDefinition,
} from '~/composables/useDashboardWidgets'
import type { MinRole, WidgetVisibilitySetting } from '~/types/dashboard'

const props = withDefaults(
  defineProps<{
    visible: boolean
    widgets: WidgetDefinition[]
    isVisible: (key: string) => boolean
    scopeType?: 'personal' | 'team' | 'organization'
    scopeId?: number
    isAdminOrDeputy?: boolean
  }>(),
  {
    scopeType: 'personal',
    scopeId: undefined,
    isAdminOrDeputy: false,
  },
)

const emit = defineEmits<{
  'update:visible': [value: boolean]
  toggle: [key: string]
  reorder: [fromIndex: number, toIndex: number]
}>()

const { t } = useI18n()
const notification = useNotification()

const showRoleTab = computed(
  () => props.isAdminOrDeputy && props.scopeType !== 'personal' && !!props.scopeId,
)

const activeTab = ref(0)

// === ロール別設定 (管理者タブ) ===
const scopeIdRef = computed(() => props.scopeId ?? 0)
const visibility = useDashboardWidgetVisibility(
  props.scopeType === 'organization' ? 'organization' : 'team',
  scopeIdRef,
)

const pendingMinRoles = ref<Record<string, MinRole>>({})
const showDowngradeConfirm = ref(false)
const downgradeContext = ref<{ widgetKey: string; widgetLabel: string } | null>(null)

watch(
  () => visibility.settings.value,
  (settings: WidgetVisibilitySetting[]) => {
    const next: Record<string, MinRole> = {}
    for (const s of settings) {
      next[s.widget_key] = s.min_role
    }
    pendingMinRoles.value = next
  },
  { deep: true },
)

watch(
  () => props.visible,
  async (open: boolean) => {
    if (open && showRoleTab.value && visibility.settings.value.length === 0) {
      try {
        await visibility.fetch()
      } catch {
        // エラーは settings の error ref に格納される。inline 表示で扱う
      }
    }
  },
)

const roleOptions = computed(() => [
  { label: t('dashboard.widget_visibility.min_role_public'), value: 'PUBLIC' as MinRole },
  { label: t('dashboard.widget_visibility.min_role_supporter'), value: 'SUPPORTER' as MinRole },
  { label: t('dashboard.widget_visibility.min_role_member'), value: 'MEMBER' as MinRole },
])

interface ManagedWidgetRow {
  widget: WidgetDefinition
  backendKey: string
  defaultMinRole: MinRole
  currentMinRole: MinRole
  isDefault: boolean
}

const managedRows = computed<ManagedWidgetRow[]>(() => {
  if (!showRoleTab.value) return []
  const rows: ManagedWidgetRow[] = []
  for (const w of props.widgets) {
    const backendKey = backendKeyForWidget(
      w.key,
      props.scopeType === 'organization' ? 'organization' : 'team',
    )
    if (!backendKey) continue
    const defaultRole = WidgetDefaultMinRoleMap[backendKey] ?? 'MEMBER'
    const current = pendingMinRoles.value[backendKey] ?? defaultRole
    rows.push({
      widget: w,
      backendKey,
      defaultMinRole: defaultRole,
      currentMinRole: current,
      isDefault: current === defaultRole,
    })
  }
  return rows
})

function attemptChangeRole(row: ManagedWidgetRow, next: MinRole) {
  if (next === 'PUBLIC' && row.currentMinRole !== 'PUBLIC') {
    downgradeContext.value = { widgetKey: row.backendKey, widgetLabel: row.widget.label }
    pendingMinRoles.value = { ...pendingMinRoles.value, [row.backendKey]: next }
    showDowngradeConfirm.value = true
    return
  }
  pendingMinRoles.value = { ...pendingMinRoles.value, [row.backendKey]: next }
}

function cancelDowngrade() {
  if (downgradeContext.value) {
    const key = downgradeContext.value.widgetKey
    const original =
      visibility.settings.value.find((s: WidgetVisibilitySetting) => s.widget_key === key)?.min_role ??
      WidgetDefaultMinRoleMap[key] ??
      'MEMBER'
    pendingMinRoles.value = { ...pendingMinRoles.value, [key]: original }
  }
  downgradeContext.value = null
  showDowngradeConfirm.value = false
}

function confirmDowngrade() {
  downgradeContext.value = null
  showDowngradeConfirm.value = false
}

function resetRow(row: ManagedWidgetRow) {
  pendingMinRoles.value = { ...pendingMinRoles.value, [row.backendKey]: row.defaultMinRole }
}

function resetAll() {
  const next: Record<string, MinRole> = {}
  for (const row of managedRows.value) {
    next[row.backendKey] = row.defaultMinRole
  }
  pendingMinRoles.value = next
}

async function save() {
  const updates = managedRows.value.map((r) => ({
    widget_key: r.backendKey,
    min_role: pendingMinRoles.value[r.backendKey] ?? r.defaultMinRole,
  }))
  try {
    await visibility.save(updates)
    notification.success(t('dashboard.widget_visibility.save_success'))
  } catch {
    notification.error(t('dashboard.widget_visibility.save_error'))
  }
}

// === 既存: 自分の表示/非表示 (drag-and-drop) ===
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
    emit('reorder', dragIndex.value, index)
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
  <Dialog
    :visible="visible"
    :header="$t('dashboard.widget_settings.dialog_header')"
    modal
    :style="{ width: showRoleTab ? '640px' : '450px' }"
    @update:visible="emit('update:visible', $event)"
  >
    <Tabs v-if="showRoleTab" v-model:value="activeTab">
      <TabList>
        <Tab :value="0">{{ $t('dashboard.widget_visibility.tab_self') }}</Tab>
        <Tab :value="1">
          {{ $t('dashboard.widget_visibility.tab_role_based') }}
          <Tag
            :value="$t('dashboard.widget_visibility.admin_only_badge')"
            severity="warning"
            class="ml-2"
          />
        </Tab>
      </TabList>
      <TabPanels>
        <TabPanel :value="0">
          <p class="mb-4 text-sm text-surface-500">
            ドラッグで並び替え、スイッチで表示・非表示を切り替えられます。
          </p>
          <div class="space-y-1">
            <div
              v-for="(w, index) in widgets"
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
                :model-value="isVisible(w.key)"
                @update:model-value="emit('toggle', w.key)"
              />
            </div>
          </div>
        </TabPanel>

        <TabPanel :value="1">
          <Message v-if="visibility.error.value" severity="error" class="mb-3 text-sm">
            {{ visibility.error.value }}
          </Message>

          <div v-if="visibility.loading.value" class="space-y-2">
            <Skeleton v-for="n in 5" :key="n" height="3rem" />
          </div>

          <div v-else-if="managedRows.length === 0" class="py-6 text-center text-sm text-surface-500">
            このスコープでロール別管理対象のウィジェットがありません
          </div>

          <div v-else class="space-y-2">
            <div
              v-for="row in managedRows"
              :key="row.backendKey"
              class="flex flex-col gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-600 sm:flex-row sm:items-center"
            >
              <div class="min-w-0 flex-1">
                <p class="text-sm font-medium">{{ row.widget.label }}</p>
                <p class="text-xs text-surface-500">{{ row.widget.description }}</p>
              </div>
              <SelectButton
                :model-value="row.currentMinRole"
                :options="roleOptions"
                option-label="label"
                option-value="value"
                :allow-empty="false"
                size="small"
                @update:model-value="(v: MinRole) => attemptChangeRole(row, v)"
              />
              <button
                v-if="!row.isDefault"
                v-tooltip.top="`デフォルト: ${row.defaultMinRole}`"
                class="text-primary"
                aria-label="デフォルトに戻す"
                @click="resetRow(row)"
              >
                <i class="pi pi-refresh text-xs" />
              </button>
              <Tag
                v-else
                :value="$t('dashboard.widget_visibility.is_default_badge')"
                severity="secondary"
                class="text-xs"
              />
            </div>
          </div>

          <div class="mt-4 flex items-center justify-between">
            <Button
              :label="$t('dashboard.widget_visibility.reset_all')"
              severity="secondary"
              text
              size="small"
              :disabled="visibility.saving.value || visibility.loading.value"
              @click="resetAll"
            />
            <Button
              :label="$t('dashboard.widget_settings.save')"
              icon="pi pi-save"
              :loading="visibility.saving.value"
              :disabled="visibility.loading.value"
              @click="save"
            />
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <template v-else>
      <p class="mb-4 text-sm text-surface-500">
        ドラッグで並び替え、スイッチで表示・非表示を切り替えられます。
      </p>
      <div class="space-y-1">
        <div
          v-for="(w, index) in widgets"
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
          <ToggleSwitch :model-value="isVisible(w.key)" @update:model-value="emit('toggle', w.key)" />
        </div>
      </div>
    </template>

    <Dialog
      v-model:visible="showDowngradeConfirm"
      :header="$t('dashboard.widget_visibility.downgrade_to_public_title')"
      modal
      :style="{ width: '480px' }"
    >
      <p class="text-sm">
        {{
          $t('dashboard.widget_visibility.downgrade_to_public_body', {
            widget: downgradeContext?.widgetLabel ?? '',
          })
        }}
      </p>
      <template #footer>
        <Button label="キャンセル" text @click="cancelDowngrade" />
        <Button
          :label="$t('dashboard.widget_visibility.downgrade_confirm')"
          severity="danger"
          @click="confirmDowngrade"
        />
      </template>
    </Dialog>
  </Dialog>
</template>
