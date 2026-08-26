<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
import { useTeamMembers } from '~/composables/team/useTeamMembers'
import { useTeamCrud } from '~/composables/team/useTeamCrud'

/**
 * 回覧板 作成モーダル（③ 作成モーダル）。
 *
 * <p>一覧（CirculationList）の `@create` で「回覧作成」ボタンが押されたときに開く。
 * タイトル・本文・あて先（チームメンバーから複数選択）・回覧モードを入力し、回覧を
 * 作成（DRAFT）→ そのまま開始（activate）して「回覧中」で一覧に出す。別ページ遷移はしない
 * （ダイアログ完結）。</p>
 *
 * <p>BE の実 DTO に合わせて実装している:</p>
 * <ul>
 *   <li>作成: `POST /api/v1/teams/{teamId}/circulations`（CreateDocumentRequest =
 *     {title, body, circulationMode?, priority?, dueDate?, recipients:[{userId, sortOrder}]}）。
 *     teamId は数値 ID（slug ではない）のため、開いた時点で slug → 数値 ID を解決する。</li>
 *   <li>開始: `POST /api/v1/teams/{teamId}/circulations/{documentId}/activate`</li>
 *   <li>メンバー一覧: `GET /api/v1/teams/{slug}/members`（あて先選択肢）</li>
 * </ul>
 *
 * <p>回覧モードは BE の `CirculationMode` enum（SIMULTANEOUS / SEQUENTIAL / HYBRID）に合わせる。
 * - SIMULTANEOUS: 全員 sortOrder=0（一斉回覧）
 * - SEQUENTIAL: 選択順に sortOrder=0,1,2,…（全員順番に回覧）
 * - HYBRID: 先頭 N 人が sortOrder=0,1,…,N-1（順番）、残り全員が sortOrder=N（一斉）。
 *   sequentialCount=N を送信ボディに含める（BE が 1 ≤ N < あて先数 を検証する）。
 * SEQUENTIAL / HYBRID 時の回覧順は recipients の sortOrder で表現し、並べ替えできる。</p>
 */
const props = defineProps<{
  /** v-model:visible。 */
  visible: boolean
  /** チームの slug（メンバー一覧 EP・数値 ID 解決に使用）。 */
  teamSlug: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 作成 + 開始が完了したとき。親が一覧を再取得できるよう通知する。 */
  created: []
}>()

const { t } = useI18n()
const { showError, showSuccess } = useNotification()
const { getMembers } = useTeamMembers()
const { getTeam } = useTeamCrud()
const { createScopedCirculation, activateCirculation } = useCirculationApi()

type CirculationMode = 'SIMULTANEOUS' | 'SEQUENTIAL' | 'HYBRID'
type CirculationPriority = 'NORMAL' | 'URGENT'

// === フォーム状態 ===
const title = ref('')
const body = ref('')
const circulationMode = ref<CirculationMode>('SIMULTANEOUS')
const priority = ref<CirculationPriority>('NORMAL')
const dueDate = ref<string>('')

// === あて先選択状態 ===
const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)
/** 選択済みあて先の userId 配列。SEQUENTIAL 時はこの並び順が回覧順（先頭から sortOrder 0,1,2,…）。 */
const selectedUserIds = ref<number[]>([])

/** HYBRID モード: 先頭 N 人（順番）の人数。1 ≤ N < あて先数。 */
const sequentialCount = ref<number>(1)

const submitting = ref(false)

const MAX_TITLE = 200

const modeOptions = computed(() => [
  { value: 'SIMULTANEOUS' as const, label: t('circulation.create.mode.SIMULTANEOUS') },
  { value: 'SEQUENTIAL' as const, label: t('circulation.create.mode.SEQUENTIAL') },
  { value: 'HYBRID' as const, label: t('circulation.create.mode.HYBRID') },
])

const priorityOptions = computed(() => [
  { value: 'NORMAL' as const, label: t('circulation.create.priority.NORMAL') },
  { value: 'URGENT' as const, label: t('circulation.create.priority.URGENT') },
])

/** 選択済みあて先を選択順に並べた MemberResponse 配列（順次回覧の並び順表示用）。 */
const selectedMembers = computed<MemberResponse[]>(() => {
  const byId = new Map(members.value.map(m => [m.userId, m]))
  return selectedUserIds.value
    .map(id => byId.get(id))
    .filter((m): m is MemberResponse => m !== undefined)
})

/** 並び順 UI（moveUp/moveDown）を表示するモードか（SEQUENTIAL または HYBRID）。 */
const showOrder = computed(() =>
  circulationMode.value === 'SEQUENTIAL' || circulationMode.value === 'HYBRID',
)

/** HYBRID モードか。 */
const isHybrid = computed(() => circulationMode.value === 'HYBRID')

/** HYBRID 時の sequentialCount の有効な最大値（あて先数 - 1）。 */
const maxSequentialCount = computed(() => Math.max(selectedUserIds.value.length - 1, 1))

/** HYBRID 時の sequentialCount バリデーションエラー。 */
const sequentialCountError = computed(() => {
  if (!isHybrid.value) return null
  const n = sequentialCount.value
  const recipientCount = selectedUserIds.value.length
  if (recipientCount <= 1) {
    return t('circulation.create.error.hybrid_needs_two_or_more')
  }
  if (n < 1 || n >= recipientCount) {
    return t('circulation.create.error.sequential_count_range', { max: recipientCount - 1 })
  }
  return null
})

// === バリデーション ===
const titleError = computed(() => {
  const trimmed = title.value.trim()
  if (trimmed.length === 0) return t('circulation.create.error.title_required')
  if (trimmed.length > MAX_TITLE) return t('circulation.create.error.title_too_long')
  return null
})
const bodyError = computed(() =>
  body.value.trim().length === 0 ? t('circulation.create.error.body_required') : null,
)
const recipientsError = computed(() =>
  selectedUserIds.value.length === 0 ? t('circulation.create.error.recipients_required') : null,
)
const canSubmit = computed(
  () =>
    !submitting.value &&
    titleError.value === null &&
    bodyError.value === null &&
    recipientsError.value === null &&
    sequentialCountError.value === null,
)

function displayMemberLabel(m: MemberResponse): string {
  return m.displayName
}

function toggleMember(userId: number) {
  const idx = selectedUserIds.value.indexOf(userId)
  if (idx >= 0) {
    selectedUserIds.value.splice(idx, 1)
  } else {
    selectedUserIds.value.push(userId)
  }
}

function isSelected(userId: number): boolean {
  return selectedUserIds.value.includes(userId)
}

/** SEQUENTIAL 時の並び替え: 選択済みあて先を 1 つ上へ。 */
function moveUp(index: number) {
  if (index <= 0) return
  const arr = selectedUserIds.value
  const tmp = arr[index]!
  arr[index] = arr[index - 1]!
  arr[index - 1] = tmp
}

/** SEQUENTIAL 時の並び替え: 選択済みあて先を 1 つ下へ。 */
function moveDown(index: number) {
  const arr = selectedUserIds.value
  if (index >= arr.length - 1) return
  const tmp = arr[index]!
  arr[index] = arr[index + 1]!
  arr[index + 1] = tmp
}

async function loadMembers() {
  membersLoading.value = true
  try {
    const res = await getMembers(props.teamSlug, { size: 200 })
    members.value = res.data
  } catch {
    showError(t('circulation.create.error.members_load_failed'))
    members.value = []
  } finally {
    membersLoading.value = false
  }
}

/** フォームを初期状態に戻す。 */
function resetForm() {
  title.value = ''
  body.value = ''
  circulationMode.value = 'SIMULTANEOUS'
  priority.value = 'NORMAL'
  dueDate.value = ''
  selectedUserIds.value = []
  sequentialCount.value = 1
}

/**
 * 選択あて先から recipients ペイロードを構築する。
 * - SIMULTANEOUS: 全員 sortOrder=0（同時回覧）
 * - SEQUENTIAL: 選択順に sortOrder=0,1,2,…（順次回覧）
 * - HYBRID: 先頭 N 人が sortOrder=0,1,…,N-1（順番）、残り全員が sortOrder=N（一斉）
 */
function buildRecipients(): Array<{ userId: number; sortOrder: number }> {
  if (circulationMode.value === 'SEQUENTIAL') {
    return selectedUserIds.value.map((userId, index) => ({ userId, sortOrder: index }))
  }
  if (circulationMode.value === 'HYBRID') {
    const n = sequentialCount.value
    return selectedUserIds.value.map((userId, index) => ({
      userId,
      sortOrder: index < n ? index : n,
    }))
  }
  return selectedUserIds.value.map(userId => ({ userId, sortOrder: 0 }))
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    // 作成 EP の teamId は数値 ID（slug ではない）。slug から数値 ID を解決する。
    const teamRes = await getTeam(props.teamSlug)
    const teamId = teamRes.data.id

    const requestBody: Record<string, unknown> = {
      title: title.value.trim(),
      body: body.value.trim(),
      circulationMode: circulationMode.value,
      priority: priority.value,
      recipients: buildRecipients(),
    }
    if (dueDate.value) {
      requestBody.dueDate = dueDate.value
    }
    // HYBRID 時のみ sequentialCount を送信（BE: 1 ≤ N < あて先数 を検証）。
    if (circulationMode.value === 'HYBRID') {
      requestBody.sequentialCount = sequentialCount.value
    }

    const created = await createScopedCirculation('team', teamId, requestBody)
    const documentId = created.data.id

    // 作成直後に開始（activate）して「回覧中」で一覧に出す。
    await activateCirculation('team', teamId, documentId)

    showSuccess(t('circulation.create.success'))
    resetForm()
    emit('created')
    emit('update:visible', false)
  } catch {
    showError(t('circulation.create.failed'))
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
}

// visible が true になったらメンバーを取得してフォームを初期化する。
watch(
  () => props.visible,
  (isVisible) => {
    if (isVisible) {
      resetForm()
      loadMembers()
    }
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('circulation.create.title')"
    :modal="true"
    :closable="true"
    :style="{ width: '600px', maxWidth: '95vw' }"
    :draggable="false"
    @update:visible="close"
  >
    <div class="flex flex-col gap-5 py-1">
      <!-- タイトル -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium" for="circ-create-title">
          {{ t('circulation.create.field.title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText
          id="circ-create-title"
          v-model="title"
          :maxlength="MAX_TITLE"
          :placeholder="t('circulation.create.field.title_placeholder')"
          class="w-full"
        />
        <p class="text-xs text-surface-400">{{ title.trim().length }} / {{ MAX_TITLE }}</p>
      </div>

      <!-- 本文 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium" for="circ-create-body">
          {{ t('circulation.create.field.body') }}
          <span class="text-red-500">*</span>
        </label>
        <Textarea
          id="circ-create-body"
          v-model="body"
          rows="5"
          auto-resize
          :placeholder="t('circulation.create.field.body_placeholder')"
          class="w-full text-sm"
        />
      </div>

      <!-- 回覧モード -->
      <div class="flex flex-col gap-1">
        <span class="text-sm font-medium">
          {{ t('circulation.create.field.mode') }}
          <span class="text-red-500">*</span>
        </span>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="opt in modeOptions"
            :key="opt.value"
            type="button"
            class="rounded-lg border px-3 py-1.5 text-sm transition-colors"
            :class="circulationMode === opt.value
              ? 'border-primary-500 bg-primary-100 text-primary-700'
              : 'border-surface-300 bg-surface-0 hover:border-primary-300'"
            @click="circulationMode = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
        <p class="text-xs text-surface-400">
          {{ t(`circulation.create.mode_hint.${circulationMode}`) }}
        </p>
      </div>

      <!-- HYBRID: 順番回覧の人数（先頭 N 人） -->
      <div v-if="isHybrid" class="flex flex-col gap-1">
        <label class="text-sm font-medium" for="circ-create-seq-count">
          {{ t('circulation.create.field.sequential_count') }}
          <span class="text-red-500">*</span>
        </label>
        <template v-if="selectedUserIds.length <= 1">
          <p class="text-xs text-surface-400">
            {{ t('circulation.create.hybrid_seq_count_needs_two') }}
          </p>
        </template>
        <template v-else>
          <InputNumber
            id="circ-create-seq-count"
            v-model="sequentialCount"
            :min="1"
            :max="maxSequentialCount"
            :placeholder="t('circulation.create.field.sequential_count_placeholder')"
            show-buttons
            button-layout="horizontal"
            class="w-36"
          />
          <p class="text-xs text-surface-400">
            {{ t('circulation.create.hybrid_seq_count_hint', { max: maxSequentialCount }) }}
          </p>
        </template>
      </div>

      <!-- あて先選択 -->
      <div class="flex flex-col gap-2">
        <span class="text-sm font-medium">
          {{ t('circulation.create.field.recipients') }}
          <span class="text-red-500">*</span>
          <span class="ml-1 text-xs font-normal text-surface-400">
            {{ t('circulation.create.recipients_selected', { count: selectedUserIds.length }) }}
          </span>
        </span>

        <div v-if="membersLoading" class="flex justify-center py-4">
          <LoadingBounce />
        </div>
        <div v-else-if="members.length === 0" class="text-sm text-surface-400">
          {{ t('circulation.create.no_members') }}
        </div>
        <div
          v-else
          class="max-h-48 overflow-y-auto rounded-lg border border-surface-200 p-1"
        >
          <button
            v-for="m in members"
            :key="m.userId"
            type="button"
            class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm transition-colors hover:bg-surface-50"
            @click="toggleMember(m.userId)"
          >
            <Checkbox
              :model-value="isSelected(m.userId)"
              :binary="true"
              readonly
              @click.stop="toggleMember(m.userId)"
            />
            <span class="truncate">{{ displayMemberLabel(m) }}</span>
          </button>
        </div>

        <!-- SEQUENTIAL / HYBRID: 回覧順（並べ替え可能） -->
        <div v-if="showOrder && selectedMembers.length > 0" class="mt-1">
          <p class="mb-1 text-xs font-medium text-surface-500">
            {{ t('circulation.create.order_title') }}
          </p>
          <ul class="flex flex-col gap-1">
            <template
              v-for="(m, index) in selectedMembers"
              :key="m.userId"
            >
              <!-- HYBRID: 境界線（先頭 N 人 → 残り一斉）を区切り表示 -->
              <li
                v-if="isHybrid && index === sequentialCount"
                class="flex items-center gap-2 py-0.5"
              >
                <div class="h-px flex-1 bg-primary-200" />
                <span class="text-xs text-primary-500">
                  {{ t('circulation.create.hybrid_boundary') }}
                </span>
                <div class="h-px flex-1 bg-primary-200" />
              </li>
              <li
                class="flex items-center gap-2 rounded-lg border px-2 py-1.5 text-sm"
                :class="isHybrid && index >= sequentialCount
                  ? 'border-surface-100 bg-surface-50'
                  : 'border-surface-200'"
              >
                <span
                  class="w-5 text-center text-xs font-semibold"
                  :class="isHybrid && index >= sequentialCount ? 'text-surface-300' : 'text-surface-400'"
                >
                  {{ isHybrid && index >= sequentialCount ? '―' : index + 1 }}
                </span>
                <span class="min-w-0 flex-1 truncate">{{ displayMemberLabel(m) }}</span>
                <Button
                  icon="pi pi-arrow-up"
                  text
                  rounded
                  size="small"
                  :disabled="index === 0"
                  :aria-label="t('circulation.create.move_up')"
                  @click="moveUp(index)"
                />
                <Button
                  icon="pi pi-arrow-down"
                  text
                  rounded
                  size="small"
                  :disabled="index === selectedMembers.length - 1"
                  :aria-label="t('circulation.create.move_down')"
                  @click="moveDown(index)"
                />
              </li>
            </template>
          </ul>
        </div>
      </div>

      <!-- 優先度・締切 -->
      <div class="flex flex-wrap gap-4">
        <div class="flex flex-col gap-1">
          <span class="text-sm font-medium">{{ t('circulation.create.field.priority') }}</span>
          <div class="flex gap-2">
            <button
              v-for="opt in priorityOptions"
              :key="opt.value"
              type="button"
              class="rounded-lg border px-3 py-1.5 text-sm transition-colors"
              :class="priority === opt.value
                ? 'border-primary-500 bg-primary-100 text-primary-700'
                : 'border-surface-300 bg-surface-0 hover:border-primary-300'"
              @click="priority = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium" for="circ-create-due">
            {{ t('circulation.create.field.due_date') }}
          </label>
          <InputText
            id="circ-create-due"
            v-model="dueDate"
            type="date"
            class="w-44"
          />
        </div>
      </div>

      <!-- バリデーションメッセージ -->
      <div v-if="titleError || bodyError || recipientsError || sequentialCountError" class="flex flex-col gap-1">
        <p v-if="titleError" class="text-xs text-red-500">{{ titleError }}</p>
        <p v-if="bodyError" class="text-xs text-red-500">{{ bodyError }}</p>
        <p v-if="recipientsError" class="text-xs text-red-500">{{ recipientsError }}</p>
        <p v-if="sequentialCountError" class="text-xs text-red-500">{{ sequentialCountError }}</p>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="t('circulation.create.cancel')"
          text
          :disabled="submitting"
          @click="close"
        />
        <Button
          :label="t('circulation.create.submit')"
          icon="pi pi-send"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        />
      </div>
    </template>
  </Dialog>
</template>
