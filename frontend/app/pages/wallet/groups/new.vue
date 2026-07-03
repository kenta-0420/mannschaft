<script setup lang="ts">
import type { UserPointCardListItem } from '~/types/pointCard'

/**
 * F18 グループ作成ページ（設計書 §7.1 / §8.1）。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>name / emoji / 含めるカード（チェックボックス選択、最大 20）</li>
 *   <li>保存（{@link useWalletApi#createGroup}）</li>
 * </ul>
 *
 * <p>20 枚上限はバックエンドでも検証されるが、フロントでも警告表示する
 * （API 呼び出し前にチェックして対処療法的にエラーを握りつぶさない、明示する）。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const router = useRouter()
const walletApi = useWalletApi()

const MAX_CARDS_PER_GROUP = 20

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const name = ref('')
const emoji = ref('')
const selectedIds = ref<Set<string>>(new Set())

const cards = ref<UserPointCardListItem[]>([])
const loadingCards = ref(true)

const saving = ref(false)
const saveError = ref<string | null>(null)
const validationError = ref<string | null>(null)

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

const selectedCount = computed(() => selectedIds.value.size)
const overLimit = computed(() => selectedCount.value > MAX_CARDS_PER_GROUP)
const canSave = computed(() => name.value.trim().length > 0 && !overLimit.value && !saving.value)

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

function toggle(cardId: string) {
  if (selectedIds.value.has(cardId)) {
    selectedIds.value.delete(cardId)
  }
  else {
    selectedIds.value.add(cardId)
  }
  // Set への変更を Vue に伝えるためにトリガー
  selectedIds.value = new Set(selectedIds.value)
}

async function loadCards() {
  loadingCards.value = true
  try {
    cards.value = await walletApi.listCards()
  }
  catch (e) {
    console.error('[wallet/groups/new] failed to load cards', e)
  }
  finally {
    loadingCards.value = false
  }
}

async function save() {
  validationError.value = null
  saveError.value = null
  if (!name.value.trim()) {
    validationError.value = t('wallet.group_form.validation_name_required')
    return
  }
  if (overLimit.value) {
    validationError.value = t('wallet.group_form.validation_max_cards')
    return
  }
  saving.value = true
  try {
    const created = await walletApi.createGroup({
      name: name.value.trim(),
      emoji: emoji.value.trim() || null,
      cardIds: Array.from(selectedIds.value),
    })
    await router.push(`/wallet/groups/${created.id}`)
  }
  catch (e) {
    console.error('[wallet/groups/new] save failed', e)
    saveError.value = t('wallet.group_form.save_failed')
  }
  finally {
    saving.value = false
  }
}

function cancel() {
  router.back()
}

onMounted(loadCards)
</script>

<template>
  <div class="group-new">
    <PageHeader :title="t('wallet.group_form.title_new')" />

    <section class="group-new__section">
      <div class="group-new__field">
        <label for="group-name" class="group-new__label">
          {{ t('wallet.group_form.name') }} <span class="group-new__required">*</span>
        </label>
        <InputText
          id="group-name"
          v-model="name"
          class="w-full"
          :placeholder="t('wallet.group_form.name_placeholder')"
          :maxlength="50"
        />
      </div>
      <div class="group-new__field">
        <label for="group-emoji" class="group-new__label">{{ t('wallet.group_form.emoji') }}</label>
        <InputText
          id="group-emoji"
          v-model="emoji"
          class="w-24"
          :placeholder="t('wallet.group_form.emoji_placeholder')"
          :maxlength="4"
        />
      </div>
    </section>

    <section class="group-new__section">
      <h2 class="group-new__section-title">
        {{ t('wallet.group_form.cards') }}
        <span class="group-new__section-hint">{{ t('wallet.group_form.max_cards') }}</span>
      </h2>
      <p class="group-new__selected-count">
        {{ t('wallet.group_form.selected_count', { count: selectedCount }) }}
      </p>

      <div v-if="loadingCards" class="group-new__loading">…</div>

      <p v-else-if="cards.length === 0" class="group-new__empty">
        {{ t('wallet.group_form.no_cards_available') }}
      </p>

      <ul v-else class="group-new__card-list">
        <li v-for="card in cards" :key="card.id">
          <label class="group-new__card-row" :for="`card-check-${card.id}`">
            <Checkbox
              :input-id="`card-check-${card.id}`"
              :model-value="selectedIds.has(card.id)"
              binary
              :disabled="!selectedIds.has(card.id) && selectedCount >= MAX_CARDS_PER_GROUP"
              @update:model-value="() => toggle(card.id)"
            />
            <span class="group-new__card-name">{{ card.displayName }}</span>
            <span v-if="card.providerDisplayName && card.providerDisplayName !== card.displayName" class="group-new__card-provider">
              {{ card.providerDisplayName }}
            </span>
          </label>
        </li>
      </ul>

      <p v-if="overLimit" class="group-new__error" role="alert">
        {{ t('wallet.group_form.validation_max_cards') }}
      </p>
    </section>

    <p v-if="validationError" class="group-new__error" role="alert">
      {{ validationError }}
    </p>
    <p v-if="saveError" class="group-new__error" role="alert">
      {{ saveError }}
    </p>

    <div class="group-new__footer">
      <Button
        :label="t('wallet.group_form.cancel')"
        severity="secondary"
        class="flex-1"
        :disabled="saving"
        @click="cancel"
      />
      <Button
        :label="t('wallet.group_form.save')"
        class="flex-1"
        :disabled="!canSave"
        :loading="saving"
        @click="save"
      />
    </div>
  </div>
</template>

<style scoped>
.group-new {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.group-new__section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.group-new__section-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0;
}
.group-new__section-hint {
  text-transform: none;
  font-weight: 400;
  letter-spacing: 0;
  margin-left: 0.5rem;
}
.group-new__selected-count {
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.group-new__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.group-new__label {
  font-size: 0.8125rem;
  font-weight: 600;
}
.group-new__required {
  color: #dc2626;
}
.group-new__loading,
.group-new__empty {
  text-align: center;
  padding: 1.5rem 0.5rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.group-new__card-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 50vh;
  overflow-y: auto;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  border-radius: 0.5rem;
}
.group-new__card-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 0.625rem;
  padding: 0.625rem 0.75rem;
  cursor: pointer;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
}
.group-new__card-row:last-child {
  border-bottom: none;
}
.group-new__card-row:has(input:disabled) {
  opacity: 0.5;
  cursor: not-allowed;
}
.group-new__card-name {
  font-weight: 600;
  font-size: 0.9375rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-new__card-provider {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-new__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
.group-new__footer {
  display: flex;
  gap: 0.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--p-surface-200, #e5e7eb);
}
</style>
