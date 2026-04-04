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

  <div v-else class="space-y-8">
    <!-- スコープ別設定 -->
    <SectionCard title="チーム・組織別の通知">
      <p class="mb-4 text-sm text-surface-500">
        所属するチーム・組織ごとに通知のミュートを設定できます。
      </p>
      <div class="space-y-3">
        <div
          v-for="pref in scopePrefs"
          :key="`${pref.scopeType}-${pref.scopeId}`"
          class="flex items-center justify-between rounded-lg border border-surface-100 p-3 dark:border-surface-700"
        >
          <div>
            <p class="text-sm font-medium">{{ pref.scopeName }}</p>
            <p class="text-xs text-surface-500">
              {{ pref.scopeType === 'TEAM' ? 'チーム' : '組織' }}
            </p>
          </div>
          <div class="flex items-center gap-3">
            <Tag
              :value="pref.isMuted ? 'ミュート中' : '受信中'"
              :severity="pref.isMuted ? 'warn' : 'success'"
              class="text-xs"
            />
            <ToggleSwitch v-model="pref.isMuted" @update:model-value="onToggleScopeMute(pref)" />
          </div>
        </div>
        <p v-if="scopePrefs.length === 0" class="py-4 text-center text-surface-400">
          所属するチーム・組織がありません
        </p>
      </div>
    </SectionCard>

    <!-- 種別別設定 -->
    <SectionCard
      v-for="(prefs, category) in groupedTypePrefs"
      :key="category"
      :title="String(category)"
    >
      <div class="space-y-1">
        <div
          v-for="tp in prefs"
          :key="tp.notificationType"
          class="flex items-center justify-between rounded-lg border border-surface-100 px-4 py-3 dark:border-surface-700"
        >
          <span class="text-sm font-medium">{{ tp.label }}</span>
          <div class="flex items-center gap-5">
            <label class="flex items-center gap-2 text-sm text-surface-500">
              <Checkbox v-model="tp.inAppEnabled" :binary="true" />
              アプリ内
            </label>
            <label class="flex items-center gap-2 text-sm text-surface-500">
              <Checkbox v-model="tp.pushEnabled" :binary="true" />
              プッシュ
            </label>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- 保存ボタン -->
    <div class="flex justify-end">
      <Button
        label="設定を保存"
        icon="pi pi-check"
        :loading="saving"
        @click="saveTypePreferences"
      />
    </div>
  </div>
</template>
