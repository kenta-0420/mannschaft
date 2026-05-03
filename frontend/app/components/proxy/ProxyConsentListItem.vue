<script setup lang="ts">
import type { ProxyInputConsent } from '~/types/proxy-input'

const props = defineProps<{
  consent: ProxyInputConsent
  selected: boolean
}>()

const emit = defineEmits<{
  select: [consent: ProxyInputConsent]
}>()

const { t } = useI18n()

/** 日付文字列を yyyy/MM/dd 形式にフォーマット */
function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/** consentMethod の i18n キーへのマッピング */
const consentMethodKey = computed(() => {
  const keyMap: Record<string, string> = {
    PAPER_SIGNED: 'proxy.consent.method.paper_signed',
    WITNESSED_ORAL: 'proxy.consent.method.witnessed_oral',
    DIGITAL_SIGNATURE: 'proxy.consent.method.digital_signature',
    GUARDIAN_BY_COURT: 'proxy.consent.method.guardian_by_court',
  }
  return keyMap[props.consent.consentMethod] ?? props.consent.consentMethod
})

/** 機能スコープの i18n ラベルを返す */
function scopeLabel(scope: string): string {
  const keyMap: Record<string, string> = {
    SURVEY: 'proxy.scope.survey',
    SCHEDULE_ATTENDANCE: 'proxy.scope.schedule_attendance',
    SHIFT_REQUEST: 'proxy.scope.shift_request',
    ANNOUNCEMENT_READ: 'proxy.scope.announcement_read',
    PARKING_APPLICATION: 'proxy.scope.parking_application',
    CIRCULAR: 'proxy.scope.circular',
  }
  const key = keyMap[scope]
  return key ? t(key) : scope
}

function handleSelect() {
  emit('select', props.consent)
}
</script>

<template>
  <div
    role="button"
    tabindex="0"
    class="cursor-pointer rounded-lg border p-3 transition-colors"
    :class="
      selected
        ? 'border-primary bg-primary-50 dark:bg-primary-950'
        : 'border-surface-200 hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800'
    "
    @click="handleSelect"
    @keydown.enter="handleSelect"
    @keydown.space.prevent="handleSelect"
  >
    <!-- ヘッダー行: 同意書ID + 選択中タグ -->
    <div class="mb-2 flex items-center justify-between gap-2">
      <span class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('proxy.consent.title') }} #{{ consent.id }}
      </span>
      <Tag v-if="selected" :value="t('proxy.consent.status.active')" severity="success" />
    </div>

    <!-- 被代理住民ID -->
    <div class="mb-1 text-xs text-surface-500 dark:text-surface-400">
      {{ t('proxy.consent.subjectUserId') }}: {{ consent.subjectUserId }}
    </div>

    <!-- 同意方法 -->
    <div class="mb-2 text-xs text-surface-600 dark:text-surface-300">
      {{ t('proxy.consent.method.label') }}: {{ t(consentMethodKey) }}
    </div>

    <!-- 有効期間 -->
    <div class="mb-2 flex items-center gap-1 text-xs text-surface-500 dark:text-surface-400">
      <i class="pi pi-calendar text-xs" />
      <span>
        {{ formatDate(consent.effectiveFrom) }} 〜 {{ formatDate(consent.effectiveUntil) }}
      </span>
    </div>

    <!-- 機能スコープ一覧 -->
    <div class="flex flex-wrap gap-1">
      <Tag
        v-for="scope in consent.scopes"
        :key="scope"
        :value="scopeLabel(scope)"
        severity="secondary"
        class="text-xs"
      />
    </div>
  </div>
</template>
