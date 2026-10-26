<script setup lang="ts">
import { getWeatherIconPath } from '~/constants/weatherIconMap'
import type { WeatherErrorCode, WeatherForecastResponse } from '~/types/weather'

/**
 * F02.10 天気ウィジェット — 今日・明日・明後日の天気を表示するウィジェット。
 *
 * 設計書: docs/features/F02.10_weather_widget.md §6
 *
 * 2026-05-18: WeatherAPI.com 無料プラン上限 3 日に合わせ、2 列から 3 列グリッドへ拡張。
 */

const { t } = useI18n()
const { getDashboardWeather, refreshWeatherLocation } = useWeatherApi()
const { formatRelative } = useRelativeTime()

const forecast = ref<WeatherForecastResponse | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const errorCode = ref<WeatherErrorCode | null>(null)

/** 列ラベルキー（インデックス順）。i18n キー: dashboard.weather.today / tomorrow / day_after_tomorrow */
const DAY_LABEL_KEYS = ['today', 'tomorrow', 'day_after_tomorrow'] as const

async function load() {
  loading.value = true
  errorCode.value = null
  try {
    forecast.value = await getDashboardWeather()
  } catch (err: unknown) {
    forecast.value = null
    // エラーコードを取り出す
    const code = extractErrorCode(err)
    errorCode.value = code
  } finally {
    loading.value = false
  }
}

async function handleRefresh() {
  refreshing.value = true
  errorCode.value = null
  try {
    await refreshWeatherLocation()
    await load()
  } catch (err: unknown) {
    const code = extractErrorCode(err)
    errorCode.value = code
  } finally {
    refreshing.value = false
  }
}

function extractErrorCode(err: unknown): WeatherErrorCode | null {
  if (err && typeof err === 'object' && 'data' in err) {
    const data = (err as { data?: { error_code?: string } }).data
    if (data?.error_code) {
      return data.error_code as WeatherErrorCode
    }
  }
  return 'WEATHER_PROVIDER_UNAVAILABLE'
}

/** 月/日 形式に変換（例: "2026-05-09" → "5/9"）*/
function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** インデックスに対応する列ラベル（無効な index は空文字）。 */
function labelKey(index: number): string {
  return DAY_LABEL_KEYS[index] ?? ''
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('dashboard.weather.title')"
    icon="pi pi-cloud"
    :loading="loading"
    :refreshable="true"
    @refresh="handleRefresh"
  >
    <!-- 正常表示 -->
    <div v-if="forecast && !errorCode">
      <!-- ヘッダー: 地名 -->
      <p class="mb-3 truncate text-sm font-medium text-surface-600 dark:text-surface-300">
        {{ forecast.placeName }}
      </p>

      <!-- 3カラムグリッド: 今日・明日・明後日（狭い画面では縦積み） -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-3 sm:gap-2">
        <div
          v-for="(day, idx) in forecast.forecasts"
          :key="day.date || idx"
          class="flex flex-col items-center gap-1"
        >
          <p class="text-xs font-semibold text-surface-500 dark:text-surface-400">
            {{ t(`dashboard.weather.${labelKey(idx)}`) }}
            <span v-if="day.date" class="ml-1">
              {{ formatDate(day.date) }}
            </span>
          </p>
          <img
            :src="getWeatherIconPath(day.iconKey)"
            class="h-12 w-12 motion-reduce:transition-none"
            :aria-label="t(`dashboard.weather.condition.${day.iconKey}`, day.conditionText)"
            :alt="t(`dashboard.weather.condition.${day.iconKey}`, day.conditionText)"
          >
          <!-- XSS対策: v-text でバインド（設計書 §5.1 / §7.7） -->
          <p class="text-center text-xs text-surface-600 dark:text-surface-300" v-text="day.conditionText" />
          <div class="mx-auto mt-1 inline-grid grid-cols-[auto_auto] gap-x-3 gap-y-1 text-xs">
            <span class="text-surface-500">{{ t('dashboard.weather.max') }}</span>
            <span class="font-medium">{{ day.maxTempC.toFixed(1) }}°</span>
            <span class="text-surface-500">{{ t('dashboard.weather.min') }}</span>
            <span class="font-medium">{{ day.minTempC.toFixed(1) }}°</span>
            <span class="text-surface-500">{{ t('dashboard.weather.humidity') }}</span>
            <span class="font-medium">{{ day.avgHumidity }}%</span>
            <span class="text-surface-500">{{ t('dashboard.weather.chance_of_rain') }}</span>
            <span class="font-medium">{{ day.chanceOfRain }}%</span>
          </div>
        </div>
      </div>

      <!-- フッター: fetched_at + stale 警告 -->
      <div
        class="mt-3 border-t pt-2 text-xs"
        :class="
          forecast.isStale
            ? 'border-amber-200 dark:border-amber-700'
            : 'border-surface-100 dark:border-surface-700'
        "
      >
        <p
          class="text-surface-400 dark:text-surface-500"
          :class="{ 'text-amber-600 dark:text-amber-400': forecast.isStale }"
        >
          {{ t('dashboard.weather.fetched_at') }}:
          {{ formatRelative(forecast.fetchedAt) }}
        </p>
        <p
          v-if="forecast.isStale"
          class="mt-0.5 text-amber-600 dark:text-amber-400"
        >
          {{ t('dashboard.weather.stale_warning') }}
        </p>
        <p class="mt-1 text-surface-300 dark:text-surface-600">
          {{ t('dashboard.weather.powered_by') }}
        </p>
      </div>
    </div>

    <!-- エラー表示 -->
    <div v-else-if="errorCode" class="flex flex-col gap-3 py-2">
      <div class="flex items-start gap-2">
        <i class="pi pi-exclamation-triangle mt-0.5 shrink-0 text-amber-500" />
        <p class="text-sm text-surface-600 dark:text-surface-300">
          <template v-if="errorCode === 'POSTAL_CODE_MISSING'">
            {{ t('dashboard.weather.error_postal_missing') }}
          </template>
          <template v-else-if="errorCode === 'POSTAL_CODE_NOT_FOUND'">
            {{ t('dashboard.weather.error_postal_not_found') }}
          </template>
          <template v-else-if="errorCode === 'COUNTRY_NOT_SUPPORTED'">
            {{ t('dashboard.weather.error_country_not_supported') }}
          </template>
          <template v-else>
            {{ t('dashboard.weather.error_provider_unavailable') }}
          </template>
        </p>
      </div>

      <!-- リトライボタン（プロバイダーエラー） -->
      <Button
        v-if="errorCode === 'WEATHER_PROVIDER_UNAVAILABLE'"
        :label="t('dashboard.weather.refresh')"
        icon="pi pi-refresh"
        text
        size="small"
        :loading="refreshing"
        @click="load"
      />
    </div>
  </DashboardWidgetCard>
</template>
