<script setup lang="ts">
import type { NotificationPreference, NotificationTypePreference } from '~/types/notification'

const { getPreferences, updatePreferences, getTypePreferences, updateTypePreferences } =
  useNotificationApi()
const { showSuccess, showError } = useNotification()

const scopePrefs = ref<NotificationPreference[]>([])
const typePrefs = ref<NotificationTypePreference[]>([])
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const [scopeRes, typeRes] = await Promise.all([getPreferences(), getTypePreferences()])
    scopePrefs.value = scopeRes.data
    typePrefs.value = typeRes.data
  } catch {
    showError('設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function onToggleScopeMute(pref: NotificationPreference) {
  try {
    const update = { scopeType: pref.scopeType, scopeId: pref.scopeId, isMuted: !pref.isMuted }
    await updatePreferences(update)
    pref.isMuted = !pref.isMuted
  } catch {
    showError('更新に失敗しました')
  }
}

async function saveTypePreferences() {
  saving.value = true
  try {
    await updateTypePreferences(
      typePrefs.value.map((tp) => ({
        notificationType: tp.notificationType,
        inAppEnabled: tp.inAppEnabled,
        pushEnabled: tp.pushEnabled,
      })),
    )
    showSuccess('通知設定を保存しました')
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

// グループ化
const groupedTypePrefs = computed(() => {
  const groups: Record<string, NotificationTypePreference[]> = {}
  for (const tp of typePrefs.value) {
    const cat = tp.category || 'その他'
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(tp)
  }
  return groups
})

onMounted(() => load())
</script>

<template>
  <div v-if="loading" class="flex justify-center py-8">
    <ProgressSpinner style="width: 40px; height: 40px" />
  </div>

  <div v-else class="flex flex-col gap-6">
    <!-- スコープ別設定 -->
    <div>
      <h3 class="mb-3 text-lg font-semibold">チーム・組織別の通知</h3>
      <div class="flex flex-col gap-2">
        <div
          v-for="pref in scopePrefs"
          :key="`${pref.scopeType}-${pref.scopeId}`"
          class="flex items-center justify-between rounded-lg border border-surface-200 px-4 py-3"
        >
          <div>
            <p class="text-sm font-medium">{{ pref.scopeName }}</p>
            <p class="text-xs text-surface-400">
              {{ pref.scopeType === 'TEAM' ? 'チーム' : '組織' }}
            </p>
          </div>
          <div class="flex items-center gap-2">
            <span class="text-xs text-surface-400">{{
              pref.isMuted ? 'ミュート中' : '受信中'
            }}</span>
            <ToggleSwitch v-model="pref.isMuted" @update:model-value="onToggleScopeMute(pref)" />
          </div>
        </div>
        <p v-if="scopePrefs.length === 0" class="text-sm text-surface-400">
          所属するチーム・組織がありません
        </p>
      </div>
    </div>

    <!-- 種別別設定 -->
    <div>
      <h3 class="mb-3 text-lg font-semibold">通知種別ごとの設定</h3>
      <div v-for="(prefs, category) in groupedTypePrefs" :key="category" class="mb-4">
        <p class="mb-2 text-sm font-medium text-surface-500">{{ category }}</p>
        <div class="flex flex-col gap-1 rounded-lg border border-surface-200">
          <div
            v-for="tp in prefs"
            :key="tp.notificationType"
            class="flex items-center justify-between border-b border-surface-100 px-4 py-2 last:border-b-0"
          >
            <span class="text-sm">{{ tp.label }}</span>
            <div class="flex items-center gap-4">
              <label class="flex items-center gap-1 text-xs text-surface-400">
                <Checkbox v-model="tp.inAppEnabled" :binary="true" />
                アプリ内
              </label>
              <label class="flex items-center gap-1 text-xs text-surface-400">
                <Checkbox v-model="tp.pushEnabled" :binary="true" />
                プッシュ
              </label>
            </div>
          </div>
        </div>
      </div>
      <div class="flex justify-end">
        <Button label="保存" :loading="saving" @click="saveTypePreferences" />
      </div>
    </div>
  </div>
</template>
