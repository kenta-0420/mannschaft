<script setup lang="ts">
import type {
  NotificationPreference,
  NotificationTypePreference,
  NotificationTypePreferenceUpdateEntry,
} from '~/types/notification'

const {
  getPreferences,
  updatePreferences,
  getTypePreferences,
  updateTypePreferences,
  getSettings,
  updateSettings,
} = useNotificationApi()
const { showSuccess, showError } = useNotification()
const { t } = useI18n()

const scopePrefs = ref<NotificationPreference[]>([])
const typePrefs = ref<NotificationTypePreference[]>([])
const priorityAutoDelivery = ref(true)
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const [scopeRes, typeRes, settingsRes] = await Promise.all([
      getPreferences(),
      getTypePreferences(),
      getSettings(),
    ])
    scopePrefs.value = scopeRes.data
    typePrefs.value = typeRes.data
    priorityAutoDelivery.value = settingsRes.data.priorityAutoDelivery
  } catch {
    showError(t('settings.notification.load_error'))
  } finally {
    loading.value = false
  }
}

// === グローバル: 優先度による自動配信 ===
async function onToggleAutoDelivery(next: boolean) {
  const prev = priorityAutoDelivery.value
  try {
    await updateSettings({ priorityAutoDelivery: next })
    priorityAutoDelivery.value = next
    showSuccess(t('settings.notification.save_success'))
  } catch {
    priorityAutoDelivery.value = prev
    showError(t('settings.notification.save_error'))
  }
}

// === スコープ別: 受信トグル ===
async function onToggleScopeEnabled(pref: NotificationPreference, next: boolean) {
  const prev = pref.isEnabled
  try {
    await updatePreferences({
      scopeType: pref.scopeType,
      scopeId: pref.scopeId,
      isEnabled: next,
    })
    pref.isEnabled = next
  } catch {
    pref.isEnabled = prev
    showError(t('settings.notification.save_error'))
  }
}

// === 種別別: 展開/折りたたみ ===
function onExpand(tp: NotificationTypePreference) {
  if (tp.isLocked) return
  tp.channelOverride = true
  // 展開時の初期値: 単一トグルが OFF なら両チャネル OFF、ON なら現値（既定 true）を踏襲
  if (!tp.isEnabled) {
    tp.inAppEnabled = false
    tp.pushEnabled = false
  }
}

function onCollapse(tp: NotificationTypePreference) {
  tp.channelOverride = false
  // 折りたたみ時: いずれかのチャネルが ON なら受信中とみなす
  tp.isEnabled = tp.inAppEnabled || tp.pushEnabled
}

function buildEntry(tp: NotificationTypePreference): NotificationTypePreferenceUpdateEntry {
  if (tp.channelOverride) {
    return {
      notificationType: tp.notificationType,
      channelOverride: true,
      inAppEnabled: tp.inAppEnabled,
      pushEnabled: tp.pushEnabled,
    }
  }
  return {
    notificationType: tp.notificationType,
    channelOverride: false,
    isEnabled: tp.isEnabled,
  }
}

async function saveTypePreferences() {
  saving.value = true
  try {
    await updateTypePreferences(
      // URGENT（ロック）種別は送信対象外（BE 側でもスキップされるが無駄な送信を避ける）
      typePrefs.value.filter((tp) => !tp.isLocked).map(buildEntry),
    )
    showSuccess(t('settings.notification.save_success'))
  } catch {
    showError(t('settings.notification.save_error'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="loading" class="flex justify-center py-8">
    <LoadingBounce />
  </div>

  <div v-else class="space-y-8">
    <!-- グローバル: 優先度による自動配信 -->
    <SectionCard :title="$t('settings.notification.global_section_title')">
      <div
        class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
      >
        <div class="pr-4">
          <p class="text-sm font-medium">
            {{ $t('settings.notification.priority_auto_delivery_label') }}
          </p>
          <p class="text-xs text-surface-500">
            {{ $t('settings.notification.priority_auto_delivery_desc') }}
          </p>
        </div>
        <ToggleSwitch
          :model-value="priorityAutoDelivery"
          @update:model-value="onToggleAutoDelivery(Boolean($event))"
        />
      </div>
    </SectionCard>

    <!-- スコープ別設定 -->
    <SectionCard :title="$t('settings.notification.scope_section_title')">
      <p class="mb-4 text-sm text-surface-500">
        {{ $t('settings.notification.scope_section_desc') }}
      </p>
      <div class="space-y-3">
        <div
          v-for="pref in scopePrefs"
          :key="`${pref.scopeType}-${pref.scopeId}`"
          class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-600"
        >
          <div>
            <p class="text-sm font-medium">{{ pref.scopeName }}</p>
            <p class="text-xs text-surface-500">
              {{
                pref.scopeType === 'TEAM'
                  ? $t('settings.notification.scope_type_team')
                  : $t('settings.notification.scope_type_organization')
              }}
            </p>
          </div>
          <div class="flex items-center gap-3">
            <Tag
              :value="
                pref.isEnabled
                  ? $t('settings.notification.scope_receiving')
                  : $t('settings.notification.scope_muted')
              "
              :severity="pref.isEnabled ? 'success' : 'warn'"
              class="text-xs"
            />
            <ToggleSwitch
              :model-value="pref.isEnabled"
              @update:model-value="onToggleScopeEnabled(pref, Boolean($event))"
            />
          </div>
        </div>
        <p v-if="scopePrefs.length === 0" class="py-4 text-center text-surface-400">
          {{ $t('settings.notification.scope_empty') }}
        </p>
      </div>
    </SectionCard>

    <!-- 種別別設定（ハイブリッド: 単一トグル / 展開で Dual） -->
    <SectionCard :title="$t('settings.notification.type_section_title')">
      <p class="mb-4 text-sm text-surface-500">
        {{ $t('settings.notification.type_section_desc') }}
      </p>
      <div class="space-y-2">
        <div
          v-for="tp in typePrefs"
          :key="tp.notificationType"
          class="rounded-lg border border-surface-100 px-4 py-3 dark:border-surface-600"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium">{{ tp.label }}</span>
              <Tag
                v-if="tp.isLocked"
                :value="$t('settings.notification.locked_tag')"
                severity="info"
                class="text-xs"
              />
            </div>

            <!-- URGENT（ロック）: グレーアウト・展開不可 -->
            <div v-if="tp.isLocked" class="flex items-center gap-3">
              <span class="text-xs text-surface-400">
                {{ $t('settings.notification.locked_desc') }}
              </span>
              <ToggleSwitch :model-value="true" disabled />
            </div>

            <!-- 単一モード -->
            <div v-else-if="!tp.channelOverride" class="flex items-center gap-3">
              <button
                type="button"
                class="text-xs text-primary-500 hover:underline"
                @click="onExpand(tp)"
              >
                {{ $t('settings.notification.expand') }}
              </button>
              <ToggleSwitch v-model="tp.isEnabled" />
            </div>

            <!-- Dual モード: 折りたたみボタン -->
            <div v-else class="flex items-center gap-3">
              <button
                type="button"
                class="text-xs text-surface-500 hover:underline"
                @click="onCollapse(tp)"
              >
                {{ $t('settings.notification.collapse') }}
              </button>
            </div>
          </div>

          <!-- Dual モード: アプリ内 / プッシュ 2トグル -->
          <div
            v-if="tp.channelOverride && !tp.isLocked"
            class="mt-3 flex items-center gap-6 border-t border-surface-100 pt-3 dark:border-surface-600"
          >
            <label class="flex items-center gap-2 text-sm text-surface-500">
              <Checkbox v-model="tp.inAppEnabled" :binary="true" />
              {{ $t('settings.notification.channel_in_app') }}
            </label>
            <label class="flex items-center gap-2 text-sm text-surface-500">
              <Checkbox v-model="tp.pushEnabled" :binary="true" />
              {{ $t('settings.notification.channel_push') }}
            </label>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- 保存ボタン -->
    <div class="flex justify-end">
      <Button
        :label="$t('settings.notification.save_button')"
        icon="pi pi-check"
        :loading="saving"
        @click="saveTypePreferences"
      />
    </div>
  </div>
</template>
