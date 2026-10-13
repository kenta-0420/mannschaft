<script setup lang="ts">
/**
 * error.vue — Nuxt のエラーページ（404 / 500 等）。表示言語に追従する（CMP-261007-2053）。
 *
 * - ログイン画面風の中央カード（auth レイアウト）。認証ストアには触れない
 *   （認証初期化前・失敗時にも描画できなければならないため）。
 * - statusCode 404 は not_found_*、それ以外は generic_*。
 * - statusCode 以外の情報（message / statusMessage / stack / data）は一切描画しない。
 *   他テナント資源の 404 と不存在 URL の 404 を画面から区別できなくするため（情報漏洩防止）。
 * - i18n の初期化に失敗していても、固定のフォールバック文言と復帰操作を出す（生のキーは出さない）。
 */
interface ErrorPageProps {
  error: {
    statusCode?: number
    statusMessage?: string
    message?: string
    stack?: string
    data?: unknown
    url?: string
  }
}

const props = defineProps<ErrorPageProps>()

type Translate = (key: string) => string

let translate: Translate | undefined
let localeRef: { value?: string } | undefined
try {
  const i18n = useI18n()
  translate = typeof i18n.t === 'function' ? (i18n.t as Translate) : undefined
  localeRef = i18n.locale as { value?: string } | undefined
} catch {
  // i18n が初期化できていない場合はフォールバック文言で描画する（握りつぶしではなく縮退表示）
  translate = undefined
}

// i18n 失敗時だけ使う固定の縮退文言（通常経路では error_page.* を引く）
const FALLBACK = {
  not_found_title: 'ページが見つかりません',
  not_found_description: 'お探しのページは存在しないか、移動または削除された可能性があります。',
  generic_title: 'エラーが発生しました',
  generic_description: 'しばらくしてからもう一度お試しください。',
  back_home: 'ホームへ戻る',
} as const

type FallbackKey = keyof typeof FALLBACK

const text = (key: FallbackKey): string => {
  if (!translate) return FALLBACK[key]
  try {
    const fullKey = `error_page.${key}`
    const value = translate(fullKey)
    return value && value !== fullKey ? value : FALLBACK[key]
  } catch {
    return FALLBACK[key]
  }
}

const isNotFound = computed(() => props.error?.statusCode === 404)
const title = computed(() => text(isNotFound.value ? 'not_found_title' : 'generic_title'))
const description = computed(() => text(isNotFound.value ? 'not_found_description' : 'generic_description'))
const backHome = computed(() => text('back_home'))

useHead(() => ({
  htmlAttrs: { lang: localeRef?.value || 'ja' },
}))

const goHome = () => clearError({ redirect: '/' })
</script>

<template>
  <NuxtLayout name="auth">
    <div class="flex flex-col items-center gap-4 text-center" data-testid="error-page">
      <h2 class="text-xl font-bold" data-testid="error-page-title">{{ title }}</h2>
      <p class="text-sm text-surface-600 dark:text-surface-300" data-testid="error-page-description">
        {{ description }}
      </p>
      <Button :label="backHome" data-testid="error-page-home" @click="goHome" />
    </div>
  </NuxtLayout>
</template>
