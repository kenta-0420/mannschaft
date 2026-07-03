<script setup lang="ts">
import type {
  PointCardGroupDetail,
  UserPointCardListItem,
} from '~/types/pointCard'

/**
 * F18 グループ編集ページ（設計書 §7.1 / §8.1）。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>名前・絵文字編集</li>
 *   <li>含まれるカードの並び替え（上下ボタン）</li>
 *   <li>カード追加 / 削除（チェックボックス UI で「他のカード」セクションから追加）</li>
 *   <li>削除ボタン（カード本体は消えない旨注意表示）</li>
 *   <li>「提示モードを開始」ボタンで {@code /wallet/groups/[id]/show} へ遷移（show.vue は第五陣で実装）</li>
 * </ul>
 *
 * <p>並び替えは設計書要件「ドラッグ&ドロップ or 上下ボタン」のうち、後者を採用。
 * モバイルでの操作確実性と Mannschaft 既存 UI の傾向に合わせる。</p>
 *
 * <p>編集内容は「保存」を押すまでサーバーに送信しない。逐次 PATCH を避けて、
 * 一回の PATCH で `cardIds`（並び順を含む）と `name`/`emoji` を差し替える。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const walletApi = useWalletApi()

const MAX_CARDS_PER_GROUP = 20
const groupId = computed(() => route.params.id as string)

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const group = ref<PointCardGroupDetail | null>(null)
const allCards = ref<UserPointCardListItem[]>([])
const loading = ref(true)
const loadError = ref(false)

const draftName = ref('')
const draftEmoji = ref('')
/** 現在のグループに含めるカード ID 配列（並び順保持）。 */
const draftCardIds = ref<string[]>([])

const saving = ref(false)
const saveError = ref<string | null>(null)
const validationError = ref<string | null>(null)

const showDeleteConfirm = ref(false)
const deleting = ref(false)

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

/** カード ID → 表示用ライト DTO のマップ（グループ詳細とカード一覧を統合） */
const cardLookup = computed(() => {
  const map = new Map<string, { displayName: string; providerDisplayName: string | null }>()
  for (const c of allCards.value) {
    map.set(c.id, {
      displayName: c.displayName,
      providerDisplayName: c.providerDisplayName,
    })
  }
  // group.items も displayName を持つので、未登録カードに対するフォールバック値として混ぜる
  if (group.value) {
    for (const item of group.value.items) {
      if (!map.has(item.cardId)) {
        map.set(item.cardId, {
          displayName: item.displayName,
          providerDisplayName: item.providerDisplayName,
        })
      }
    }
  }
  return map
})

/** 並び順を保ったグループ内カード */
const orderedItems = computed(() =>
  draftCardIds.value.map((id, idx) => ({
    id,
    index: idx,
    info: cardLookup.value.get(id) ?? { displayName: id, providerDisplayName: null },
  })),
)

/** グループに未追加のカード（追加候補） */
const availableCards = computed(() => {
  const inGroup = new Set(draftCardIds.value)
  return allCards.value.filter((c) => !inGroup.has(c.id))
})

const selectedCount = computed(() => draftCardIds.value.length)
const overLimit = computed(() => selectedCount.value > MAX_CARDS_PER_GROUP)
const canSave = computed(
  () => draftName.value.trim().length > 0 && !overLimit.value && !saving.value,
)

// ─────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const [g, cs] = await Promise.all([
      walletApi.getGroup(groupId.value),
      walletApi.listCards(),
    ])
    group.value = g
    allCards.value = cs
    draftName.value = g.name
    draftEmoji.value = g.emoji ?? ''
    draftCardIds.value = g.items
      .slice()
      .sort((a, b) => a.displayOrder - b.displayOrder)
      .map((it) => it.cardId)
  }
  catch (e) {
    console.error('[wallet/groups/[id]] load failed', e)
    loadError.value = true
  }
  finally {
    loading.value = false
  }
}

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

function moveUp(index: number) {
  if (index <= 0) return
  const arr = [...draftCardIds.value]
  const tmp = arr[index]
  if (tmp === undefined) return
  arr[index] = arr[index - 1] as string
  arr[index - 1] = tmp
  draftCardIds.value = arr
}

function moveDown(index: number) {
  if (index >= draftCardIds.value.length - 1) return
  const arr = [...draftCardIds.value]
  const tmp = arr[index]
  if (tmp === undefined) return
  arr[index] = arr[index + 1] as string
  arr[index + 1] = tmp
  draftCardIds.value = arr
}

function removeFromGroup(cardId: string) {
  draftCardIds.value = draftCardIds.value.filter((id) => id !== cardId)
}

function addToGroup(cardId: string) {
  if (draftCardIds.value.includes(cardId)) return
  if (draftCardIds.value.length >= MAX_CARDS_PER_GROUP) {
    validationError.value = t('wallet.group_form.validation_max_cards')
    return
  }
  draftCardIds.value = [...draftCardIds.value, cardId]
  validationError.value = null
}

async function save() {
  validationError.value = null
  saveError.value = null
  if (!draftName.value.trim()) {
    validationError.value = t('wallet.group_form.validation_name_required')
    return
  }
  if (overLimit.value) {
    validationError.value = t('wallet.group_form.validation_max_cards')
    return
  }
  saving.value = true
  try {
    group.value = await walletApi.updateGroup(groupId.value, {
      name: draftName.value.trim(),
      emoji: draftEmoji.value.trim() || null,
      cardIds: draftCardIds.value.slice(),
    })
  }
  catch (e) {
    console.error('[wallet/groups/[id]] save failed', e)
    saveError.value = t('wallet.group_form.save_failed')
  }
  finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (deleting.value) return
  deleting.value = true
  try {
    await walletApi.deleteGroup(groupId.value)
    await router.push('/wallet')
  }
  catch (e) {
    console.error('[wallet/groups/[id]] delete failed', e)
    deleting.value = false
  }
}

function startPresentation() {
  // 提示モード実装は第五陣（show.vue）。本陣ではナビゲーションのみ。
  router.push(`/wallet/groups/${groupId.value}/show`)
}

function backToWallet() {
  router.push('/wallet')
}

onMounted(load)
</script>

<template>
  <div class="group-edit">
    <PageHeader :title="t('wallet.group_form.title_edit')" back-to="/wallet" />

    <div v-if="loading" class="group-edit__loading">…</div>

    <div v-else-if="loadError || !group" class="group-edit__error" role="alert">
      <p>{{ t('wallet.group_form.not_found') }}</p>
      <Button label="←" severity="secondary" @click="backToWallet" />
    </div>

    <template v-else>
      <!-- 提示モード起動ボタン（編集中でも常設） -->
      <section class="group-edit__section">
        <Button
          :label="`▶ ${t('wallet.group_form.start_presentation')}`"
          class="group-edit__btn--accent w-full"
          :disabled="draftCardIds.length === 0"
          @click="startPresentation"
        />
      </section>

      <!-- name / emoji -->
      <section class="group-edit__section">
        <div class="group-edit__field">
          <label for="g-name" class="group-edit__label">
            {{ t('wallet.group_form.name') }} <span class="group-edit__required">*</span>
          </label>
          <InputText
            id="g-name"
            v-model="draftName"
            class="w-full"
            :placeholder="t('wallet.group_form.name_placeholder')"
            :maxlength="50"
          />
        </div>
        <div class="group-edit__field">
          <label for="g-emoji" class="group-edit__label">{{ t('wallet.group_form.emoji') }}</label>
          <InputText
            id="g-emoji"
            v-model="draftEmoji"
            class="w-24"
            :placeholder="t('wallet.group_form.emoji_placeholder')"
            :maxlength="4"
          />
        </div>
      </section>

      <!-- グループ内カード（並び替え可能） -->
      <section class="group-edit__section">
        <h2 class="group-edit__section-title">
          {{ t('wallet.group_form.cards') }}
          <span class="group-edit__section-hint">{{ t('wallet.group_form.max_cards') }}</span>
        </h2>
        <p class="group-edit__hint">{{ t('wallet.group_form.reorder_hint') }}</p>

        <ul v-if="orderedItems.length > 0" class="group-edit__card-list">
          <li v-for="(item, idx) in orderedItems" :key="item.id" class="group-edit__card-row">
            <div class="group-edit__card-info">
              <span class="group-edit__card-name">{{ item.info.displayName }}</span>
              <span
                v-if="item.info.providerDisplayName && item.info.providerDisplayName !== item.info.displayName"
                class="group-edit__card-provider"
              >
                {{ item.info.providerDisplayName }}
              </span>
            </div>
            <div class="group-edit__card-actions">
              <button
                type="button"
                class="group-edit__icon-btn"
                :disabled="idx === 0"
                :aria-label="t('wallet.group_form.move_up')"
                @click="moveUp(idx)"
              >
                ↑
              </button>
              <button
                type="button"
                class="group-edit__icon-btn"
                :disabled="idx === orderedItems.length - 1"
                :aria-label="t('wallet.group_form.move_down')"
                @click="moveDown(idx)"
              >
                ↓
              </button>
              <button
                type="button"
                class="group-edit__icon-btn group-edit__icon-btn--danger"
                :aria-label="t('wallet.group_form.remove_from_group')"
                @click="removeFromGroup(item.id)"
              >
                ×
              </button>
            </div>
          </li>
        </ul>
        <p v-else class="group-edit__empty">
          {{ t('wallet.group.no_groups_hint') }}
        </p>

        <p v-if="overLimit" class="group-edit__error" role="alert">
          {{ t('wallet.group_form.validation_max_cards') }}
        </p>
      </section>

      <!-- 追加可能なカード -->
      <section v-if="availableCards.length > 0" class="group-edit__section">
        <h2 class="group-edit__section-title">
          {{ t('wallet.group_form.add_to_group') }}
        </h2>
        <ul class="group-edit__add-list">
          <li v-for="card in availableCards" :key="card.id" class="group-edit__add-row">
            <div class="group-edit__card-info">
              <span class="group-edit__card-name">{{ card.displayName }}</span>
              <span
                v-if="card.providerDisplayName && card.providerDisplayName !== card.displayName"
                class="group-edit__card-provider"
              >
                {{ card.providerDisplayName }}
              </span>
            </div>
            <button
              type="button"
              class="group-edit__icon-btn"
              :disabled="draftCardIds.length >= MAX_CARDS_PER_GROUP"
              :aria-label="t('wallet.group_form.add_to_group')"
              @click="addToGroup(card.id)"
            >
              ＋
            </button>
          </li>
        </ul>
      </section>

      <!-- バリデーション・エラー -->
      <p v-if="validationError" class="group-edit__error" role="alert">
        {{ validationError }}
      </p>
      <p v-if="saveError" class="group-edit__error" role="alert">
        {{ saveError }}
      </p>

      <!-- 保存フッタ -->
      <div class="group-edit__footer">
        <Button
          :label="t('wallet.group_form.cancel')"
          severity="secondary"
          class="flex-1"
          :disabled="saving"
          @click="backToWallet"
        />
        <Button
          :label="t('wallet.group_form.save')"
          class="flex-1"
          :disabled="!canSave"
          :loading="saving"
          @click="save"
        />
      </div>

      <!-- 削除セクション -->
      <section class="group-edit__danger">
        <Button
          :label="t('wallet.group_form.delete')"
          severity="danger"
          outlined
          class="w-full"
          @click="showDeleteConfirm = true"
        />
      </section>
    </template>

    <!-- 削除確認モーダル -->
    <div
      v-if="showDeleteConfirm"
      class="group-edit__modal-backdrop"
      role="dialog"
      aria-modal="true"
      :aria-label="t('wallet.group_form.delete_confirm_title')"
      @click.self="showDeleteConfirm = false"
    >
      <div class="group-edit__modal">
        <h2 class="group-edit__modal-title">{{ t('wallet.group_form.delete_confirm_title') }}</h2>
        <p class="group-edit__modal-body">{{ t('wallet.group_form.delete_confirm') }}</p>
        <div class="group-edit__modal-actions">
          <Button
            :label="t('wallet.group_form.cancel')"
            severity="secondary"
            :disabled="deleting"
            @click="showDeleteConfirm = false"
          />
          <Button
            :label="t('wallet.group_form.delete_confirm_ok')"
            severity="danger"
            :disabled="deleting"
            :loading="deleting"
            @click="confirmDelete"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.group-edit {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  position: relative;
}
.group-edit__loading,
.group-edit__error {
  text-align: center;
  padding: 2rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
.group-edit__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.group-edit__section-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}
.group-edit__section-hint {
  text-transform: none;
  font-weight: 400;
  letter-spacing: 0;
  margin-left: 0.5rem;
}
.group-edit__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.group-edit__label {
  font-size: 0.8125rem;
  font-weight: 600;
}
.group-edit__required {
  color: #dc2626;
}
.group-edit__hint {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.group-edit__card-list,
.group-edit__add-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  border-radius: 0.5rem;
}
.group-edit__card-row,
.group-edit__add-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
}
.group-edit__card-row:last-child,
.group-edit__add-row:last-child {
  border-bottom: none;
}
.group-edit__card-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.group-edit__card-name {
  font-weight: 600;
  font-size: 0.9375rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-edit__card-provider {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-edit__card-actions {
  display: flex;
  gap: 0.25rem;
}
.group-edit__icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 0.375rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  background: var(--p-content-background, #fff);
  cursor: pointer;
  font-size: 1rem;
}
.group-edit__icon-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.group-edit__icon-btn--danger {
  color: #dc2626;
  border-color: #dc2626;
}
.group-edit__empty {
  text-align: center;
  padding: 1rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.group-edit__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
.group-edit__footer {
  display: flex;
  gap: 0.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
.group-edit__danger {
  margin-top: 0.5rem;
  padding-top: 0.5rem;
}
.group-edit__modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
  padding: 1rem;
}
.group-edit__modal {
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  padding: 1.25rem;
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.group-edit__modal-title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
}
.group-edit__modal-body {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--p-text-color, #111827);
}
.group-edit__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
