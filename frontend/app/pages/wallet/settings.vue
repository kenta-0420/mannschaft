<script setup lang="ts">
import TermsAcceptModal from '~/components/wallet/TermsAcceptModal.vue'
import type { PointCardUserSettings } from '~/types/pointCard'

/**
 * F18 ウォレット設定ページ。
 *
 * <p>設計書 §8.6 / §9.2 に基づき、ダークパターン回避のため「機能を無効化する」
 * 操作を上部に常設し、規約再表示と GDPR 削除導線を提供する。</p>
 *
 * <p>本フェーズではウォレット機能の完全削除 (DELETE) 自体は実装せず、
 * F12.3 GDPR フローへのリンク表示にとどめる。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const walletApi = useWalletApi()

const settings = ref<PointCardUserSettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const showTermsView = ref(false)
const showDisableConfirm = ref(false)

async function loadSettings() {
  loading.value = true
  try {
    settings.value = await walletApi.getSettings()
  } catch (error) {
    console.error('[wallet/settings] failed to load', error)
  } finally {
    loading.value = false
  }
}

async function applySettings(patch: { isEnabled?: boolean; requireBiometricOnShow?: boolean }) {
  if (!settings.value) return
  saving.value = true
  try {
    settings.value = await walletApi.updateSettings(patch)
  } catch (error) {
    console.error('[wallet/settings] failed to update', error)
    // 失敗時は再読込で UI と DB を同期する（楽観的更新の巻き戻し）
    await loadSettings()
  } finally {
    saving.value = false
  }
}

function onToggleEnabled(checked: boolean) {
  if (!checked) {
    // 無効化時は確認ダイアログ
    showDisableConfirm.value = true
    return
  }
  applySettings({ isEnabled: true })
}

function confirmDisable() {
  showDisableConfirm.value = false
  applySettings({ isEnabled: false })
}

function cancelDisable() {
  showDisableConfirm.value = false
  // 再読込で UI を元の状態に戻す（v-model が一旦 false になっているため）
  loadSettings()
}

function onToggleBiometric(checked: boolean) {
  applySettings({ requireBiometricOnShow: checked })
}

onMounted(() => {
  loadSettings()
})
</script>

<template>
  <div class="settings-page">
    <PageHeader :title="t('wallet.settings.title')" back-to="/wallet" />

    <PageLoading v-if="loading" />

    <template v-else-if="settings">
      <!-- 全般セクション -->
      <section class="settings-page__section">
        <h2 class="settings-page__section-title">{{ t('wallet.settings.section_general') }}</h2>

        <div class="settings-page__row">
          <div class="settings-page__row-label">
            <span class="settings-page__row-title">{{ t('wallet.settings.is_enabled') }}</span>
            <span class="settings-page__row-hint">{{ t('wallet.settings.is_enabled_hint') }}</span>
          </div>
          <ToggleSwitch
            :model-value="settings.isEnabled"
            :disabled="saving"
            @update:model-value="onToggleEnabled"
          />
        </div>
      </section>

      <!-- セキュリティセクション -->
      <section class="settings-page__section">
        <h2 class="settings-page__section-title">{{ t('wallet.settings.section_security') }}</h2>

        <div class="settings-page__row">
          <div class="settings-page__row-label">
            <span class="settings-page__row-title">
              {{ t('wallet.settings.require_biometric') }}
            </span>
            <span class="settings-page__row-hint">
              {{ t('wallet.settings.require_biometric_hint') }}
            </span>
          </div>
          <ToggleSwitch
            :model-value="settings.requireBiometricOnShow"
            :disabled="saving || !settings.isEnabled"
            @update:model-value="onToggleBiometric"
          />
        </div>
      </section>

      <!-- 規約・データセクション -->
      <section class="settings-page__section">
        <h2 class="settings-page__section-title">{{ t('wallet.settings.section_legal') }}</h2>

        <button type="button" class="settings-page__action-btn" @click="showTermsView = true">
          {{ t('wallet.settings.view_terms') }}
        </button>

        <NuxtLink to="/me/privacy" class="settings-page__action-btn settings-page__action-btn--danger">
          {{ t('wallet.settings.delete_all') }}
        </NuxtLink>
        <p class="settings-page__hint">{{ t('wallet.settings.delete_all_hint') }}</p>
      </section>
    </template>

    <!-- 無効化確認モーダル -->
    <div v-if="showDisableConfirm" class="settings-page__modal-backdrop" role="dialog">
      <div class="settings-page__modal">
        <h3 class="settings-page__modal-title">
          {{ t('wallet.settings.disable_confirm_title') }}
        </h3>
        <p class="settings-page__modal-body">{{ t('wallet.settings.disable_confirm_body') }}</p>
        <div class="settings-page__modal-actions">
          <Button
            :label="t('wallet.actions.cancel')"
            severity="secondary"
            @click="cancelDisable"
          />
          <Button
            :label="t('wallet.settings.disable_confirm_ok')"
            severity="danger"
            @click="confirmDisable"
          />
        </div>
      </div>
    </div>

    <TermsAcceptModal v-model="showTermsView" mode="view" />
  </div>
</template>

<style scoped>
.settings-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
}
.settings-page__section {
  margin-bottom: 2rem;
}
.settings-page__section-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.5rem;
}
.settings-page__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem;
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  margin-bottom: 0.5rem;
}
.settings-page__row-label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1;
}
.settings-page__row-title {
  font-weight: 600;
  font-size: 0.9375rem;
}
.settings-page__row-hint {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
}
.settings-page__action-btn {
  display: block;
  width: 100%;
  padding: 0.875rem 1rem;
  background: var(--p-content-background, #fff);
  border: 1px solid var(--p-surface-200, #e5e7eb);
  border-radius: 0.75rem;
  text-align: left;
  text-decoration: none;
  color: inherit;
  font-size: 0.9375rem;
  cursor: pointer;
  margin-bottom: 0.5rem;
}
.settings-page__action-btn--danger {
  color: #b91c1c;
}
.settings-page__hint {
  font-size: 0.8125rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0.25rem 0 0;
}
.settings-page__modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 1rem;
}
.settings-page__modal {
  background: var(--p-content-background, #fff);
  border-radius: 1rem;
  max-width: 420px;
  width: 100%;
  padding: 1.5rem;
}
.settings-page__modal-title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0 0 0.5rem;
}
.settings-page__modal-body {
  font-size: 0.9375rem;
  color: var(--p-text-color, #111827);
  margin: 0 0 1.25rem;
  line-height: 1.6;
}
.settings-page__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
