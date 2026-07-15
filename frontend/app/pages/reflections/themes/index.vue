<script setup lang="ts">
/**
 * F06.5 テーマ一覧（§7 #1）。テーマの CRUD 導線。
 *
 * クエリ `?create=1`（＋ slotKind/slotId/title/sourceType）で作成ダイアログを自動オープンする
 * （今日ビューの空きコマからの遷移）。
 *
 * Phase 3 追加:
 * - アーカイブ/復元ボタン（アーカイブ時は確認ダイアログを表示）。
 * - アーカイブページへの導線（PageHeader #actions にアイコン追加）。
 * - topLevelThemes を ReflectionThemeDialog へ渡す（親テーマ選択のため）。
 */
import type {
  ReflectionThemeResponse,
  ReflectionSourceType,
} from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()
const route = useRoute()
const confirm = useConfirm()

const loading = ref(true)
const themes = ref<ReflectionThemeResponse[]>([])

// ダイアログ状態
const dialogVisible = ref(false)
const editingTheme = ref<ReflectionThemeResponse | null>(null)
const presetTitle = ref<string | null>(null)
const presetSlotKind = ref<string | null>(null)
const presetSlotId = ref<number | null>(null)
const presetSourceType = ref<ReflectionSourceType | null>(null)

// Phase 3: トップレベルテーマ（親テーマ候補として渡す）
const topLevelThemes = computed(() =>
  themes.value.filter(th => !th.parentThemeId),
)

onMounted(async () => {
  await load()
  // クエリ ?create=1 で作成ダイアログを開く（空きコマからの遷移）。
  if (route.query.create === '1') {
    presetTitle.value = typeof route.query.title === 'string' ? route.query.title : null
    presetSlotKind.value = typeof route.query.slotKind === 'string' ? route.query.slotKind : null
    presetSlotId.value = typeof route.query.slotId === 'string' ? Number(route.query.slotId) : null
    editingTheme.value = null
    dialogVisible.value = true
  }
})

async function load() {
  loading.value = true
  try {
    const res = await reflectionApi.listThemes()
    themes.value = res.data ?? []
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editingTheme.value = null
  presetTitle.value = null
  presetSlotKind.value = null
  presetSlotId.value = null
  presetSourceType.value = null
  dialogVisible.value = true
}

function openEdit(theme: ReflectionThemeResponse) {
  editingTheme.value = theme
  dialogVisible.value = true
}

function onSaved() {
  load()
}

function confirmDelete(theme: ReflectionThemeResponse) {
  confirm.require({
    message: t('reflection.theme.delete_confirm'),
    header: t('reflection.theme.delete'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.theme.delete'),
    acceptClass: 'p-button-danger',
    accept: () => doDelete(theme),
  })
}

async function doDelete(theme: ReflectionThemeResponse) {
  if (!theme.id) return
  try {
    await reflectionApi.deleteTheme(theme.id)
    notification.success(t('reflection.entry.saved'))
    await load()
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
}

// Phase 3: アーカイブ確認ダイアログ
function confirmArchive(theme: ReflectionThemeResponse) {
  confirm.require({
    message: t('reflection.archive.confirm.archive'),
    header: t('reflection.archive.action.archive'),
    icon: 'pi pi-archive',
    rejectLabel: t('reflection.common.cancel'),
    acceptLabel: t('reflection.archive.action.archive'),
    accept: () => doArchive(theme),
  })
}

async function doArchive(theme: ReflectionThemeResponse) {
  if (!theme.id) return
  try {
    await reflectionApi.archiveTheme(theme.id)
    notification.success(t('reflection.archive.action.archive') + ' ✓')
    await load()
  }
  catch {
    notification.error(t('reflection.entry.save_failed'))
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <PageHeader
      :title="t('reflection.theme.list_heading')"
      back-to="/reflections"
      :back-label="t('reflection.nav.today')"
      class="flex-wrap justify-between"
    >
      <template #actions>
        <!-- Phase 3: アーカイブ閲覧ページへの導線 -->
        <Button
          v-tooltip.bottom="t('reflection.archive.label')"
          icon="pi pi-inbox"
          text
          rounded
          :aria-label="t('reflection.archive.label')"
          @click="router.push('/reflections/archive')"
        />
      </template>
      <Button :label="t('reflection.theme.create')" icon="pi pi-plus" size="small" @click="openCreate" />
    </PageHeader>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="64px" />
      <Skeleton height="64px" />
    </div>

    <SectionCard v-else-if="themes.length === 0" class="text-center">
      <p class="mb-3 text-sm text-surface-500">{{ t('reflection.theme.empty') }}</p>
      <Button :label="t('reflection.theme.create')" icon="pi pi-plus" @click="openCreate" />
    </SectionCard>

    <div v-else class="space-y-2">
      <div
        v-for="theme in themes"
        :key="theme.id"
        class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
      >
        <div
          class="min-w-0 flex-1 cursor-pointer"
          @click="router.push(`/reflections/themes/${theme.id}`)"
        >
          <div class="flex items-center gap-2">
            <span class="truncate text-sm font-medium">{{ theme.title }}</span>
            <span class="rounded bg-surface-100 px-1.5 py-0.5 text-xs text-surface-500 dark:bg-surface-700">
              {{ t(`reflection.source_type.${theme.sourceType}`) }}
            </span>
            <!-- Phase 3: 学年/学期バッジ -->
            <span
              v-if="theme.academicYear || theme.termLabel"
              class="rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
            >
              {{ [theme.academicYear, theme.termLabel].filter(Boolean).join(' ') }}
            </span>
          </div>
          <p v-if="theme.description" class="mt-0.5 truncate text-xs text-surface-500">{{ theme.description }}</p>
          <p v-if="theme.examDate" class="mt-0.5 text-xs text-amber-600">
            <i class="pi pi-calendar mr-1" />{{ theme.examDate }}
          </p>
        </div>
        <div class="flex flex-shrink-0 items-center">
          <Button icon="pi pi-pencil" text rounded :aria-label="t('reflection.theme.edit')" @click="openEdit(theme)" />
          <!-- Phase 3: アーカイブボタン -->
          <Button
            v-tooltip.top="t('reflection.archive.action.archive')"
            icon="pi pi-inbox"
            text
            rounded
            severity="secondary"
            :aria-label="t('reflection.archive.action.archive')"
            @click="confirmArchive(theme)"
          />
          <Button icon="pi pi-trash" text rounded severity="danger" :aria-label="t('reflection.theme.delete')" @click="confirmDelete(theme)" />
        </div>
      </div>
    </div>

    <ReflectionThemeDialog
      v-model:visible="dialogVisible"
      :theme="editingTheme"
      :preset-title="presetTitle"
      :preset-slot-kind="presetSlotKind"
      :preset-slot-id="presetSlotId"
      :preset-source-type="presetSourceType"
      :top-level-themes="topLevelThemes"
      @saved="onSaved"
    />
  </div>
</template>
