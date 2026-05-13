<script setup lang="ts">
import { getWeatherIconPath } from '~/constants/weatherIconMap'
import type { WeatherErrorCode, WeatherForecastResponse } from '~/types/weather'

/**
 * F02.10 天気ウィジェット — 今日・明日の天気を表示するウィジェット。
 *
 * 設計書: docs/features/F02.10_weather_widget.md §6
 */

const { t } = useI18n()
const { getDashboardWeather, refreshWeatherLocation } = useWeatherApi()
const { formatRelative } = useRelativeTime()

const forecast = ref<WeatherForecastResponse | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const errorCode = ref<WeatherErrorCode | null>(null)

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

      <!-- 2カラムグリッド: 今日・明日 -->
      <div class="grid grid-cols-2 gap-3">
        <!-- 今日 -->
        <div class="flex flex-col items-center gap-1">
          <p class="text-xs font-semibold text-surface-500 dark:text-surface-400">
            {{ t('dashboard.weather.today') }}
            <span v-if="forecast.today.date" class="ml-1">
              {{ formatDate(forecast.today.date) }}
            </span>
          </p>
          <img
            :src="getWeatherIconPath(forecast.today.iconKey)"
            class="h-12 w-12 motion-reduce:transition-none"
            :aria-label="t(`dashboard.weather.condition.${forecast.today.iconKey}`, forecast.today.conditionText)"
            :alt="t(`dashboard.weather.condition.${forecast.today.iconKey}`, forecast.today.conditionText)"
          >
          <!-- XSS対策: v-text でバインド（設計書 §5.1 / §7.7） -->
          <p class="text-center text-xs text-surface-600 dark:text-surface-300" v-text="forecast.today.conditionText" />
          <div class="mt-1 w-full space-y-1 text-xs">
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.max') }}</span>
              <span class="font-medium">{{ forecast.today.maxTempC.toFixed(1) }}°</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.min') }}</span>
              <span class="font-medium">{{ forecast.today.minTempC.toFixed(1) }}°</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.humidity') }}</span>
              <span class="font-medium">{{ forecast.today.avgHumidity }}%</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.chance_of_rain') }}</span>
              <span class="font-medium">{{ forecast.today.chanceOfRain }}%</span>
            </div>
          </div>
        </div>

        <!-- 明日 -->
        <div class="flex flex-col items-center gap-1">
          <p class="text-xs font-semibold text-surface-500 dark:text-surface-400">
            {{ t('dashboard.weather.tomorrow') }}
            <span v-if="forecast.tomorrow.date" class="ml-1">
              {{ formatDate(forecast.tomorrow.date) }}
            </span>
          </p>
          <img
            :src="getWeatherIconPath(forecast.tomorrow.iconKey)"
            class="h-12 w-12 motion-reduce:transition-none"
            :aria-label="t(`dashboard.weather.condition.${forecast.tomorrow.iconKey}`, forecast.tomorrow.conditionText)"
            :alt="t(`dashboard.weather.condition.${forecast.tomorrow.iconKey}`, forecast.tomorrow.conditionText)"
          >
          <!-- XSS対策: v-text でバインド（設計書 §5.1 / §7.7） -->
          <p class="text-center text-xs text-surface-600 dark:text-surface-300" v-text="forecast.tomorrow.conditionText" />
          <div class="mt-1 w-full space-y-1 text-xs">
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.max') }}</span>
              <span class="font-medium">{{ forecast.tomorrow.maxTempC.toFixed(1) }}°</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.min') }}</span>
              <span class="font-medium">{{ forecast.tomorrow.minTempC.toFixed(1) }}°</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.humidity') }}</span>
              <span class="font-medium">{{ forecast.tomorrow.avgHumidity }}%</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('dashboard.weather.chance_of_rain') }}</span>
              <span class="font-medium">{{ forecast.tomorrow.chanceOfRain }}%</span>
            </div>
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

      <!-- プロフィール編集リンク（郵便番号系エラー） -->
      <NuxtLink
        v-if="errorCode === 'POSTAL_CODE_MISSING' || errorCode === 'POSTAL_CODE_NOT_FOUND'"
        to="/settings/profile"
        class="text-sm text-primary hover:underline"
      >
        {{ t('dashboard.weather.edit_profile') }}
      </NuxtLink>

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
