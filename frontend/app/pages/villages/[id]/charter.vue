<script setup lang="ts">
/**
 * F17.3 村憲章（Village Charter）タブ — 村詳細 / 村憲章タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.3_village_charter.md §9（FE 設計・実機E2E耐性クリティック反映済）
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは村憲章パネル本体のみ（`chronicles.vue` を金型
 * とする薄い子パネル）。`VillageHeader` は自前で描画しない。
 *
 * # 編集可否
 *  `useVillageContext().perms.value.isAdmin`（現役 HEADMAN/ELDER）で判定する（§9.2）。
 *  BE 応答の `canEdit` も同じ意味論を返すが、本パネルは設計書の指示どおり `perms.isAdmin` を正とする。
 *
 * # 保存＝即時公開・「改正を確定」は非ゲート【§8.2/§9.3】
 *  条の追加・編集・削除・並び替えは保存した瞬間に即時公開される（下書きモードは無い）。
 *  「改正を確定」は改定日・改定履歴を刻む里程標であって、条文の可視性は一切変えない。
 *
 * # 409 ハンドリング【§9.3】
 *  version 競合が起こりうるのは PUT（層1・条単位）と PATCH order（層2・憲章全体）のみ
 *  （POST/DELETE は親 charter 行の悲観ロックで直列化され 409 を返さない・§6.3）。
 *  競合時はトースト＋最新 GET での再取得（対処療法で握りつぶさない）。
 */
import type { MembershipResponse } from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'
import { useVillageMembershipApi } from '~/composables/village/useVillageMembershipApi'
import {
  useVillageCharterApi,
  type CharterArticleResponse,
  type CharterDrafterResponse,
  type VillageCharterResponse,
} from '~/composables/village/useVillageCharterApi'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const charterApi = useVillageCharterApi()
const { listMembers } = useVillageMembershipApi()
const { handleApiError } = useErrorHandler()
const { showSuccess, showWarn, showError } = useNotification()
const { formatDate } = useDatetime()
const { confirmAction } = useConfirmDialog()

// 権限は親シェルから inject（村は再フェッチしない・§9.2）。
const { perms } = useVillageContext()
const canEdit = computed(() => perms.value.isAdmin)

// =============================================================================
// BE と揃える境界値（§18.2 Bean Validation の既定値）
// =============================================================================
const BODY_MAX = 2000
const SUPPLEMENT_MAX = 2000
const REVISION_NOTE_MAX = 200

// =============================================================================
// エラーコード抽出（recruit-categories.vue / representatives.vue と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody & { error?: ApiErrorBody }
  status?: number
  statusCode?: number
  response?: { status?: number, _data?: ApiErrorBody & { error?: ApiErrorBody } }
}

function extractErrorCode(err: unknown): string | null {
  if (typeof err !== 'object' || err === null) return null
  const e = err as ApiErrorEnvelope
  const body = e.data ?? e.response?._data
  return body?.error?.code ?? body?.errorCode ?? body?.code ?? null
}

// BE 実装（backend/.../village/VillageErrorCode.java）の確定コード。
// 設計書の暫定予約（VILLAGE_102〜106）は F17.2 W2 予約と衝突したため、実装側で繰り上げ済み。
const CHARTER_ARTICLE_VERSION_CONFLICT = 'VILLAGE_105' // 層1（PUT articles/{id}）
const CHARTER_ORDER_VERSION_CONFLICT = 'VILLAGE_106' // 層2（PATCH articles/order）
const CHARTER_DRAFTER_DUPLICATE = 'VILLAGE_108'

/**
 * 構造変更・編集系の共通エラーハンドラ。
 * 層1/層2の version 競合はトースト＋最新 GET 再取得（対処療法で握りつぶさない・§9.3）。
 * 策定者の重複は専用文言。それ以外は generic handleApiError（BE の理由付きメッセージを表示）。
 */
async function handleCharterMutationError(err: unknown, fallback: string): Promise<void> {
  const code = extractErrorCode(err)
  if (code === CHARTER_ARTICLE_VERSION_CONFLICT || code === CHARTER_ORDER_VERSION_CONFLICT) {
    showWarn(t('village.charter.error.versionConflict'))
    await loadCharter()
    return
  }
  if (code === CHARTER_DRAFTER_DUPLICATE) {
    showError(t('village.charter.error.drafterDuplicate'))
    return
  }
  handleApiError(err, fallback)
}

// =============================================================================
// 憲章取得
// =============================================================================

const charter = ref<VillageCharterResponse | null>(null)
const loading = ref(false)
const loadError = ref(false)

async function loadCharter() {
  loading.value = true
  loadError.value = false
  try {
    charter.value = await charterApi.getCharter(villageId.value)
  }
  catch (error) {
    charter.value = null
    loadError.value = true
    handleApiError(error, t('village.charter.error.loadFailed'))
  }
  finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadCharter()
})

const hasCharter = computed(() => charter.value?.hasCharter ?? false)
const articles = computed(() => charter.value?.articles ?? [])
const drafters = computed(() => charter.value?.drafters ?? [])
const revisions = computed(() => charter.value?.revisions ?? [])

// =============================================================================
// 条の追加
// =============================================================================

const newArticleBody = ref('')
const addingArticle = ref(false)

async function addArticle() {
  const body = newArticleBody.value.trim()
  if (!body || addingArticle.value) return
  addingArticle.value = true
  try {
    charter.value = await charterApi.createArticle(villageId.value, { body })
    newArticleBody.value = ''
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    addingArticle.value = false
  }
}

// =============================================================================
// 条のインライン編集（1行入力＋自動保存・§4.2/§9.3）
//  - body/supplement は article オブジェクトへ直接 v-model（reactive な入れ子オブジェクト）。
//  - blur で保存。他条・充填状態を巻き込まないよう、成功時は当該条だけ差し替える（AC-11c 独立性）。
// =============================================================================

const savingArticleIds = ref<Set<string>>(new Set())
/** 付則入力欄をユーザーが明示的に開いた条 id（既に supplement が入っている条は常に開く）。 */
const openSupplementIds = ref<Set<string>>(new Set())

function isSupplementOpen(article: CharterArticleResponse): boolean {
  return !!article.supplement || openSupplementIds.value.has(article.id)
}

function openSupplementEditor(articleId: string) {
  openSupplementIds.value.add(articleId)
}

async function saveArticle(article: CharterArticleResponse) {
  if (savingArticleIds.value.has(article.id)) return
  const trimmedBody = article.body.trim()
  if (!trimmedBody) {
    showWarn(t('village.charter.article.bodyRequired'))
    await loadCharter()
    return
  }
  savingArticleIds.value.add(article.id)
  try {
    const updated = await charterApi.updateArticle(villageId.value, article.id, {
      body: trimmedBody,
      supplement: article.supplement?.trim() ? article.supplement.trim() : undefined,
      version: article.version,
    })
    const list = charter.value?.articles
    const idx = list?.findIndex(a => a.id === article.id) ?? -1
    if (list && idx !== -1) list.splice(idx, 1, updated)
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    savingArticleIds.value.delete(article.id)
  }
}

// =============================================================================
// 条の削除
// =============================================================================

const deletingArticleId = ref<string | null>(null)

function confirmDeleteArticle(article: CharterArticleResponse) {
  confirmAction({
    message: t('village.charter.article.deleteConfirm'),
    header: t('village.charter.article.delete'),
    onAccept: () => deleteArticleConfirmed(article.id),
  })
}

async function deleteArticleConfirmed(articleId: string) {
  deletingArticleId.value = articleId
  try {
    charter.value = await charterApi.deleteArticle(villageId.value, articleId)
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    deletingArticleId.value = null
  }
}

// =============================================================================
// 条の並び替え（上下ボタン → PATCH order・層2 charterVersion 同送・§6.3/§7）
// =============================================================================

const reordering = ref(false)

async function moveArticle(index: number, direction: -1 | 1) {
  if (!charter.value || reordering.value) return
  const next = index + direction
  const list = charter.value.articles
  if (next < 0 || next >= list.length) return
  const reordered = [...list]
  const a = reordered[index]
  const b = reordered[next]
  if (!a || !b) return
  reordered[index] = b
  reordered[next] = a
  const articleIds = reordered.map(x => x.id)

  reordering.value = true
  try {
    charter.value = await charterApi.reorderArticles(villageId.value, {
      articleIds,
      charterVersion: charter.value.version ?? 0,
    })
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    reordering.value = false
  }
}

// =============================================================================
// 策定者
// =============================================================================

const villageMembers = ref<MembershipResponse[]>([])
const membersLoading = ref(false)
const membersLoadError = ref(false)

async function loadVillageMembers() {
  membersLoading.value = true
  membersLoadError.value = false
  try {
    const res = await listMembers(villageId.value, { size: 100 })
    villageMembers.value = res.content.filter(m => m.subjectType === 'USER')
  }
  catch {
    // 策定者候補の取得失敗は憲章本体の表示までは止めない（追加操作のみ不可になる）が、
    // 「村人が 0 人」と誤認させないよう失敗自体は画面に出して再取得導線を与える
    // （CLAUDE.md「症状を隠さない」）。
    villageMembers.value = []
    membersLoadError.value = true
  }
  finally {
    membersLoading.value = false
  }
}

interface MemberOption {
  value: number
  label: string
}

const memberOptions = computed<MemberOption[]>(() =>
  villageMembers.value.map(m => ({
    value: m.subjectId,
    label: m.displayName ?? `#${m.subjectId}`,
  })),
)

const selectedDrafterUserId = ref<number | null>(null)
const addingDrafter = ref(false)

async function addDrafter() {
  if (!selectedDrafterUserId.value || addingDrafter.value) return
  addingDrafter.value = true
  try {
    charter.value = await charterApi.addDrafter(villageId.value, { userId: selectedDrafterUserId.value })
    selectedDrafterUserId.value = null
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    addingDrafter.value = false
  }
}

const deletingDrafterId = ref<string | null>(null)

function confirmDeleteDrafter(drafter: CharterDrafterResponse) {
  confirmAction({
    message: t('village.charter.drafters.deleteConfirm', { name: drafter.displayName }),
    header: t('village.charter.drafters.delete'),
    onAccept: () => deleteDrafterConfirmed(drafter.id),
  })
}

async function deleteDrafterConfirmed(drafterId: string) {
  deletingDrafterId.value = drafterId
  try {
    charter.value = await charterApi.removeDrafter(villageId.value, drafterId)
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    deletingDrafterId.value = null
  }
}

// =============================================================================
// 改正を確定（POST revisions・非ゲート・§8.2/§9.3）
// =============================================================================

const revisionDialogOpen = ref(false)
const revisionNote = ref('')
const confirmingRevision = ref(false)

function openRevisionDialog() {
  revisionNote.value = ''
  revisionDialogOpen.value = true
}

async function confirmRevision() {
  if (confirmingRevision.value) return
  confirmingRevision.value = true
  try {
    charter.value = await charterApi.addRevision(villageId.value, {
      note: revisionNote.value.trim() ? revisionNote.value.trim() : undefined,
    })
    revisionDialogOpen.value = false
    showSuccess(t('village.charter.revision.confirmSuccess'))
  }
  catch (err) {
    await handleCharterMutationError(err, t('village.charter.error.mutationFailed'))
  }
  finally {
    confirmingRevision.value = false
  }
}

// 編集者のみ策定者候補（村人一覧）を取得する。親シェルは村取得をクライアントで行うため、
// 権限確定後（= 本パネルのマウント時点で既に village 解決済み・§9.0）にそのまま呼べる。
onMounted(() => {
  if (canEdit.value) void loadVillageMembers()
})
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <div class="mb-4 flex items-start justify-between gap-3">
      <div>
        <h2 class="text-xl font-bold">
          {{ t('village.charter.title') }}
        </h2>
        <p class="text-sm text-surface-500">
          {{ t('village.charter.subtitle') }}
        </p>
      </div>
    </div>

    <!-- 読込中 -->
    <div v-if="loading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>

    <!-- 読込エラー -->
    <div v-else-if="loadError" class="text-center py-12">
      <DashboardEmptyState
        icon="pi pi-exclamation-triangle"
        :message="t('village.charter.error.loadFailed')"
      >
        <template #action>
          <Button
            :label="t('village.feed.retry')"
            icon="pi pi-refresh"
            size="small"
            @click="loadCharter"
          />
        </template>
      </DashboardEmptyState>
    </div>

    <template v-else>
      <!-- 制定日・改定日 -->
      <SectionCard v-if="hasCharter" class="mb-4">
        <div class="flex flex-wrap gap-x-6 gap-y-1 text-sm text-surface-600 dark:text-surface-300">
          <span v-if="charter?.enactedAt">
            <i class="pi pi-flag mr-1" />{{ t('village.charter.enactedAt') }}: {{ formatDate(charter.enactedAt) }}
          </span>
          <span v-if="charter?.lastRevisedAt">
            <i class="pi pi-history mr-1" />{{ t('village.charter.lastRevisedAt') }}: {{ formatDate(charter.lastRevisedAt) }}
          </span>
        </div>
      </SectionCard>

      <!-- 条文一覧 -->
      <SectionCard class="mb-4" data-testid="village-charter-articles">
        <DashboardEmptyState
          v-if="articles.length === 0"
          icon="pi pi-file-edit"
          :message="t('village.charter.empty')"
        />

        <div v-else class="flex flex-col gap-4">
          <div
            v-for="(article, index) in articles"
            :key="article.id"
            class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
            :data-testid="`village-charter-article-${article.id}`"
          >
            <div class="mb-2 flex items-center justify-between gap-2">
              <span class="text-sm font-semibold text-surface-700 dark:text-surface-200">
                {{ t('village.charter.article.label', { n: article.articleNumber }) }}
              </span>
              <div v-if="canEdit" class="flex items-center gap-1">
                <Button
                  icon="pi pi-chevron-up"
                  text
                  rounded
                  size="small"
                  :aria-label="t('village.charter.reorder.up')"
                  :disabled="index === 0 || reordering"
                  @click="moveArticle(index, -1)"
                />
                <Button
                  icon="pi pi-chevron-down"
                  text
                  rounded
                  size="small"
                  :aria-label="t('village.charter.reorder.down')"
                  :disabled="index === articles.length - 1 || reordering"
                  @click="moveArticle(index, 1)"
                />
                <Button
                  icon="pi pi-trash"
                  text
                  rounded
                  size="small"
                  severity="danger"
                  :aria-label="t('village.charter.article.delete')"
                  :loading="deletingArticleId === article.id"
                  @click="confirmDeleteArticle(article)"
                />
              </div>
            </div>

            <!-- 本文（編集可: インライン textarea + blur 自動保存 / 編集不可: 生値表示） -->
            <Textarea
              v-if="canEdit"
              v-model="article.body"
              auto-resize
              :maxlength="BODY_MAX"
              :placeholder="t('village.charter.article.bodyPlaceholder')"
              class="w-full text-sm"
              rows="2"
              :data-testid="`village-charter-article-body-${article.id}`"
              @blur="saveArticle(article)"
            />
            <p v-else class="whitespace-pre-wrap text-sm">
              {{ article.body }}
            </p>

            <!-- 付則（任意） -->
            <div v-if="canEdit" class="mt-2">
              <Button
                v-if="!isSupplementOpen(article)"
                :label="t('village.charter.supplement.add')"
                icon="pi pi-plus"
                text
                size="small"
                @click="openSupplementEditor(article.id)"
              />
              <div v-else>
                <label class="mb-1 block text-xs text-surface-500">
                  {{ t('village.charter.supplement.label') }}
                </label>
                <Textarea
                  v-model="article.supplement"
                  auto-resize
                  :maxlength="SUPPLEMENT_MAX"
                  :placeholder="t('village.charter.supplement.placeholder')"
                  class="w-full text-sm"
                  rows="2"
                  @blur="saveArticle(article)"
                />
              </div>
            </div>
            <p v-else-if="article.supplement" class="mt-2 whitespace-pre-wrap text-xs text-surface-500">
              {{ t('village.charter.supplement.label') }}: {{ article.supplement }}
            </p>
          </div>

          <p v-if="canEdit && articles.length > 1" class="text-xs text-surface-400">
            {{ t('village.charter.reorder.hint') }}
          </p>
        </div>

        <!-- 条の追加（編集可のみ・v1 スコープの制限に依らず常時表示。初回は憲章を自動生成・§4.5） -->
        <div v-if="canEdit" class="mt-4 border-t border-surface-200 pt-4 dark:border-surface-700">
          <Textarea
            v-model="newArticleBody"
            auto-resize
            :maxlength="BODY_MAX"
            :placeholder="t('village.charter.article.bodyPlaceholder')"
            class="w-full text-sm"
            rows="2"
            data-testid="village-charter-new-article-body"
          />
          <Button
            :label="t('village.charter.article.add')"
            icon="pi pi-plus"
            size="small"
            class="mt-2"
            :loading="addingArticle"
            :disabled="!newArticleBody.trim()"
            data-testid="village-charter-add-article"
            @click="addArticle"
          />
        </div>
      </SectionCard>

      <!-- 策定者 -->
      <SectionCard class="mb-4">
        <h3 class="mb-2 text-sm font-semibold">
          {{ t('village.charter.drafters.title') }}
        </h3>
        <DashboardEmptyState
          v-if="drafters.length === 0"
          icon="pi pi-users"
          :message="t('village.charter.drafters.empty')"
        />
        <ul v-else class="flex flex-col gap-1">
          <li
            v-for="drafter in drafters"
            :key="drafter.id"
            class="flex items-center justify-between gap-2 text-sm"
          >
            <span>{{ drafter.displayName }}</span>
            <Button
              v-if="canEdit"
              icon="pi pi-times"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('village.charter.drafters.delete')"
              :loading="deletingDrafterId === drafter.id"
              @click="confirmDeleteDrafter(drafter)"
            />
          </li>
        </ul>

        <!-- 策定者候補の取得失敗は握りつぶさず明示し、再取得導線を出す -->
        <Message
          v-if="canEdit && membersLoadError"
          severity="warn"
          :closable="false"
          class="mt-3"
        >
          <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span>{{ t('village.charter.drafters.loadMembersFailed') }}</span>
            <Button
              :label="t('village.charter.drafters.reloadMembers')"
              icon="pi pi-refresh"
              size="small"
              text
              :loading="membersLoading"
              @click="loadVillageMembers"
            />
          </div>
        </Message>

        <div v-if="canEdit" class="mt-3 flex flex-col gap-2 sm:flex-row sm:items-center">
          <Select
            v-model="selectedDrafterUserId"
            :options="memberOptions"
            option-label="label"
            option-value="value"
            :placeholder="t('village.charter.drafters.selectPlaceholder')"
            :disabled="membersLoading || memberOptions.length === 0"
            class="w-full sm:max-w-xs"
          />
          <Button
            :label="t('village.charter.drafters.add')"
            icon="pi pi-user-plus"
            size="small"
            :disabled="!selectedDrafterUserId"
            :loading="addingDrafter"
            @click="addDrafter"
          />
        </div>
      </SectionCard>

      <!-- 改定履歴 -->
      <SectionCard>
        <div class="mb-2 flex items-center justify-between gap-2">
          <h3 class="text-sm font-semibold">
            {{ t('village.charter.revision.history') }}
          </h3>
          <Button
            v-if="canEdit && hasCharter"
            :label="t('village.charter.revision.confirm')"
            icon="pi pi-bookmark"
            size="small"
            severity="secondary"
            outlined
            @click="openRevisionDialog"
          />
        </div>
        <DashboardEmptyState
          v-if="revisions.length === 0"
          icon="pi pi-history"
          :message="t('village.charter.revision.historyEmpty')"
        />
        <ul v-else class="flex flex-col gap-1">
          <li v-for="revision in revisions" :key="revision.id" class="text-sm">
            <span class="font-medium">{{ formatDate(revision.revisedAt) }}</span>
            <span v-if="revision.note" class="ml-2 text-surface-500">{{ revision.note }}</span>
          </li>
        </ul>
      </SectionCard>
    </template>

    <!-- 「改正を確定」Dialog（改定日・改定履歴を刻む里程標。条文の可視性は変えない・§8.2） -->
    <Dialog
      v-model:visible="revisionDialogOpen"
      modal
      :header="t('village.charter.revision.confirm')"
      :style="{ width: '28rem' }"
      :draggable="false"
      :closable="!confirmingRevision"
      data-testid="village-charter-revision-dialog"
    >
      <p class="mb-3 text-sm text-surface-600 dark:text-surface-300">
        {{ t('village.charter.revision.confirmHint') }}
      </p>
      <Textarea
        v-model="revisionNote"
        auto-resize
        :maxlength="REVISION_NOTE_MAX"
        :placeholder="t('village.charter.revision.notePlaceholder')"
        class="w-full text-sm"
        rows="3"
      />
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          :disabled="confirmingRevision"
          @click="revisionDialogOpen = false"
        />
        <Button
          :label="t('village.charter.revision.confirm')"
          :loading="confirmingRevision"
          data-testid="village-charter-revision-confirm"
          @click="confirmRevision"
        />
      </template>
    </Dialog>
  </div>
</template>
