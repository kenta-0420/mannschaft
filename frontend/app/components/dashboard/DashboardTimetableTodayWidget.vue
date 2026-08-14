<script setup lang="ts">
/**
 * F03.15 Phase 3 ダッシュボード「今日の時間割」ウィジェット。
 *
 * 個人ダッシュボードに配置し、所属チーム時間割と個人時間割を時刻順でマージ表示する。
 * 各コマには折り畳み式の個人メモ（予習 / 持参物 / 自由メモ）を併載し、
 *  - メモ無し → 「＋メモを書く」ボタンでエディタを開く
 *  - メモ有り → 展開ボタンで冒頭抜粋を確認、「編集」ボタンでフル編集
 * という導線で完結させる。
 */
import type {
  DashboardTimetableToday,
  DashboardTimetableTodayItem,
  TimetableSlotKind,
  TimetableSlotUserNote,
} from '~/types/timetable-note'

const { t } = useI18n()
const api = useTimetableSlotNoteApi()

const data = ref<DashboardTimetableToday | null>(null)
const notesBySlot = ref<Map<string, TimetableSlotUserNote>>(new Map())
const expandedSlots = ref<Set<string>>(new Set())
const loading = ref(true)
/**
 * 時間割（主）の取得失敗フラグ。失敗を「今日の予定なし」に偽装しないため、空表示と区別する（Issue #2770）。
 * メモ（従）の失敗とは粒度を分ける — 「どちらか失敗＝全部失敗」にすると、
 * 取得できている時間割まで捨てて利用者に嘘をつくことになる。
 */
const timetableError = ref(false)
/** メモ（従）の取得失敗フラグ。時間割の表示は妨げず、メモ欄に限定して表面化させる。 */
const notesError = ref(false)

const editorVisible = ref(false)
const editorSlot = ref<{ kind: TimetableSlotKind, id: number, date?: string | null } | null>(null)

function slotKey(kind: TimetableSlotKind | 'TEAM' | 'PERSONAL', id: number): string {
  return `${kind}:${id}`
}

function snippet(text: string | null | undefined, max = 60): string {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}

function hasAnyNote(note: TimetableSlotUserNote | undefined): boolean {
  if (!note) return false
  return !!(note.preparation || note.items_to_bring || note.free_memo
    || (note.custom_fields && note.custom_fields.some(f => f.value)))
}

async function load() {
  loading.value = true
  timetableError.value = false
  notesError.value = false
  try {
    // 時間割（主）とメモ（従）は失敗を独立に扱う。
    // Promise.all でまとめると「どちらか1つでも失敗＝全部失敗」に粒度が粗くなり、
    // メモだけが落ちたときに取得できている時間割まで捨ててしまう（Issue #2770）。
    const [todayResult, notesResult] = await Promise.allSettled([
      api.dashboardToday(),
      api.todayNotes(),
    ])

    if (todayResult.status === 'fulfilled') {
      data.value = todayResult.value
    }
    else {
      // 取得失敗は「予定なし」と区別してユーザーに伝える（0件表示への偽装を避ける）
      timetableError.value = true
      data.value = null
    }

    if (notesResult.status === 'fulfilled') {
      const map = new Map<string, TimetableSlotUserNote>()
      const autoExpand = new Set<string>()
      for (const n of notesResult.value) {
        const key = slotKey(n.slot_kind, n.slot_id)
        map.set(key, n)
        // メモのある行は既定で展開しておく（ダッシュボードでひと目で内容を把握させる）
        if (n.preparation || n.items_to_bring || n.free_memo
          || (n.custom_fields && n.custom_fields.some(f => f.value))) {
          autoExpand.add(key)
        }
      }
      notesBySlot.value = map
      expandedSlots.value = autoExpand
    }
    else {
      // メモの失敗は「メモ無し」に偽装せず、メモ欄に限定して表面化させる
      notesError.value = true
      notesBySlot.value = new Map()
      expandedSlots.value = new Set()
    }
  }
  finally {
    loading.value = false
  }
}

function toggleExpand(item: DashboardTimetableTodayItem) {
  const key = slotKey(item.source_kind, item.slot_id)
  if (expandedSlots.value.has(key)) expandedSlots.value.delete(key)
  else expandedSlots.value.add(key)
}

function openEditor(item: DashboardTimetableTodayItem) {
  editorSlot.value = {
    kind: item.source_kind as TimetableSlotKind,
    id: item.slot_id,
    date: data.value?.date ?? null,
  }
  editorVisible.value = true
}

async function onNoteSaved() {
  editorVisible.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('personalTimetable.dashboard.title')"
    icon="pi pi-calendar"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <!-- メモ（従）だけが失敗した場合。時間割は表示したまま、メモ欄の欠落だけを控えめに知らせる -->
    <p v-if="notesError" class="mb-2 text-xs text-amber-700 dark:text-amber-400">
      {{ t('personalTimetable.dashboard.notes_load_failed') }}
    </p>

    <p v-if="timetableError" class="text-sm text-red-600 dark:text-red-400">
      {{ t('personalTimetable.dashboard.load_failed') }}
    </p>
    <p v-else-if="!data || data.items.length === 0" class="text-sm text-gray-500">
      {{ t('personalTimetable.dashboard.empty') }}
    </p>
    <ul v-else class="space-y-2">
      <li
        v-for="(item, idx) in data.items"
        :key="`${item.source_kind}-${item.slot_id}-${idx}`"
        class="rounded-lg border-2 border-l-4 border-surface-300 px-2 py-1.5 text-sm dark:border-surface-600"
        :style="item.color ? `border-left-color:${item.color}` : 'border-left-color:#cbd5e0'"
      >
        <!-- コマ本体 -->
        <div class="flex items-center">
          <span class="mr-2 text-gray-500 w-16">
            {{ item.start_time?.slice(0, 5) ?? '--:--' }}
          </span>
          <span class="font-medium mr-1">{{ item.subject_name }}</span>
          <span v-if="item.room_name" class="text-gray-500 mr-1">@ {{ item.room_name }}</span>
          <span
            class="ml-auto text-xs px-1.5 py-0.5 rounded"
            :class="item.source_kind === 'PERSONAL' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'"
          >
            {{ item.source_kind === 'PERSONAL' ? t('personalTimetable.dashboard.tag_personal') : t('personalTimetable.dashboard.tag_team') }}
          </span>
          <span v-if="item.has_attachments" class="ml-1 text-xs">📎</span>
        </div>

        <!-- メモ操作行 -->
        <div class="flex items-center gap-2 mt-1 ml-16">
          <template v-if="hasAnyNote(notesBySlot.get(slotKey(item.source_kind, item.slot_id)))">
            <button
              type="button"
              class="text-xs text-primary hover:underline"
              @click="toggleExpand(item)"
            >
              <i
                :class="expandedSlots.has(slotKey(item.source_kind, item.slot_id)) ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
                class="text-[10px] mr-0.5"
              />
              {{ t('personalTimetable.dashboard.memo_label') }}
            </button>
            <button
              type="button"
              class="text-xs text-gray-500 hover:text-primary hover:underline ml-auto"
              @click="openEditor(item)"
            >
              <i class="pi pi-pencil text-[10px] mr-0.5" />{{ t('personalTimetable.dashboard.memo_edit') }}
            </button>
          </template>
          <template v-else>
            <button
              type="button"
              class="text-xs text-gray-400 hover:text-primary hover:underline"
              @click="openEditor(item)"
            >
              <i class="pi pi-plus text-[10px] mr-0.5" />{{ t('personalTimetable.dashboard.memo_add') }}
            </button>
          </template>
        </div>

        <!-- メモ展開部 -->
        <div
          v-if="expandedSlots.has(slotKey(item.source_kind, item.slot_id)) && notesBySlot.get(slotKey(item.source_kind, item.slot_id))"
          class="mt-1 ml-16 space-y-0.5 text-xs text-gray-700 dark:text-gray-300"
        >
          <p v-if="notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.preparation">
            <span class="font-medium">{{ t('personalTimetable.notes.field_preparation') }}:</span>
            {{ snippet(notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.preparation) }}
          </p>
          <p v-if="notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.items_to_bring">
            <span class="font-medium">{{ t('personalTimetable.notes.field_items_to_bring') }}:</span>
            {{ snippet(notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.items_to_bring) }}
          </p>
          <p v-if="notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.free_memo">
            <span class="font-medium">{{ t('personalTimetable.notes.field_free_memo') }}:</span>
            {{ snippet(notesBySlot.get(slotKey(item.source_kind, item.slot_id))!.free_memo) }}
          </p>
        </div>
      </li>
    </ul>

    <!-- メモ編集モーダル -->
    <Dialog
      v-model:visible="editorVisible"
      :header="t('personalTimetable.notes.dashboard_title')"
      modal
      class="w-full max-w-2xl"
    >
      <TimetableSlotNoteEditor
        v-if="editorSlot"
        :slot-kind="editorSlot.kind"
        :slot-id="editorSlot.id"
        :target-date="editorSlot.date"
        @saved="onNoteSaved"
        @closed="editorVisible = false"
      />
    </Dialog>
  </DashboardWidgetCard>
</template>
