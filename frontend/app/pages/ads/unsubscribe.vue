<script setup lang="ts">
/**
 * F09.17 残課題 4 — 公開 unsubscribe SPA ページ。
 *
 * <p>メール末尾の配信停止リンクから到達し、ユーザーがチャネル別チェックボックスで
 * 停止したいチャネルだけを選択して確定する 3 ステップ UX を提供する
 * （設計書 §9.3 タップ動線）。</p>
 *
 * <p>このページは認証不要・SSR 無効。JWT は URL クエリパラメータから受け取り、
 * POST /api/v1/ads/unsubscribe で確定する。</p>
 */
import type { AdReceiveChannel } from '~/types/adPreferences'

definePageMeta({ auth: false })

const { t } = useI18n()
const route = useRoute()
const api = useAdPreferencesApi()

const ALL_CHANNELS: AdReceiveChannel[] = ['ANNOUNCEMENT', 'EMAIL', 'PUSH', 'BANNER']

const token = computed(() => {
  const raw = route.query.token
  if (typeof raw === 'string') {
    return raw
  }
  if (Array.isArray(raw) && raw.length > 0 && typeof raw[0] === 'string') {
    return raw[0]
  }
  return ''
})

/** 各チャネルを「停止対象に含めるか」のチェック状態（true=停止する）。初期値は全 ON。 */
const selected = ref<Record<AdReceiveChannel, boolean>>({
  ANNOUNCEMENT: true,
  EMAIL: true,
  PUSH: true,
  BANNER: true,
})

const submitting = ref(false)
const done = ref(false)
type ErrorKind = 'TOKEN_MISSING' | 'INVALID_OR_EXPIRED' | 'RATE_LIMITED' | 'UNKNOWN'
const errorKind = ref<ErrorKind | null>(null)
const disabledChannels = ref<AdReceiveChannel[]>([])
const remainingChannels = ref<AdReceiveChannel[]>([])

const selectedChannels = computed<AdReceiveChannel[]>(() =>
  ALL_CHANNELS.filter((ch) => selected.value[ch] === true),
)

const canSubmit = computed(() =>
  token.value.length > 0 && selectedChannels.value.length > 0 && !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value) {
    return
  }
  if (token.value.length === 0) {
    errorKind.value = 'TOKEN_MISSING'
    return
  }
  submitting.value = true
  errorKind.value = null
  try {
    const result = await api.submitUnsubscribe({
      token: token.value,
      channels: selectedChannels.value,
    })
    disabledChannels.value = result.disabledChannels
    remainingChannels.value = result.remainingActiveChannels
    done.value = true
  }
  catch (error: unknown) {
    errorKind.value = mapError(error)
  }
  finally {
    submitting.value = false
  }
}

function mapError(error: unknown): ErrorKind {
  const status = readStatus(error)
  if (status === 429) {
    return 'RATE_LIMITED'
  }
  if (status === 400 || status === 410) {
    return 'INVALID_OR_EXPIRED'
  }
  return 'UNKNOWN'
}

function readStatus(error: unknown): number | null {
  if (error && typeof error === 'object') {
    const e = error as { status?: unknown, response?: { status?: unknown } }
    if (typeof e.status === 'number') {
      return e.status
    }
    if (e.response && typeof e.response.status === 'number') {
      return e.response.status
    }
  }
  return null
}

// 初期表示で token が無ければエラー画面を表示
onMounted(() => {
  if (token.value.length === 0) {
    errorKind.value = 'TOKEN_MISSING'
  }
})

function channelLabel(channel: AdReceiveChannel): string {
  return t(`advertising.channel.${channel.toLowerCase()}`)
}
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-surface-50 p-4 dark:bg-surface-900">
    <div class="w-full max-w-md">
      <!-- 完了画面 -->
      <div
        v-if="done"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-check-circle mb-4 text-4xl text-green-500" />
        <h1 class="mb-3 text-xl font-bold">
          {{ $t('advertising.unsubscribe_spa.done_title') }}
        </h1>
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          {{ $t('advertising.unsubscribe_spa.done_description') }}
        </p>

        <div v-if="disabledChannels.length > 0" class="mb-4">
          <p class="mb-2 text-sm font-semibold">
            {{ $t('advertising.unsubscribe_spa.done_disabled_label') }}
          </p>
          <ul class="text-sm text-surface-700 dark:text-surface-200">
            <li v-for="ch in disabledChannels" :key="ch">
              {{ channelLabel(ch) }}
            </li>
          </ul>
        </div>

        <div v-if="remainingChannels.length > 0" class="mb-4">
          <p class="mb-2 text-sm font-semibold">
            {{ $t('advertising.unsubscribe_spa.done_remaining_label') }}
          </p>
          <ul class="text-sm text-surface-600 dark:text-surface-300">
            <li v-for="ch in remainingChannels" :key="ch">
              {{ channelLabel(ch) }}
            </li>
          </ul>
        </div>

        <p class="mt-4 text-xs text-surface-500">
          {{ $t('advertising.unsubscribe_spa.done_login_hint') }}
        </p>
      </div>

      <!-- エラー画面: token 欠落 / JWT 不正・期限切れ -->
      <div
        v-else-if="errorKind === 'TOKEN_MISSING' || errorKind === 'INVALID_OR_EXPIRED'"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-exclamation-triangle mb-4 text-4xl text-yellow-500" />
        <h1 class="mb-3 text-xl font-bold">
          {{ $t('advertising.unsubscribe_spa.error_title') }}
        </h1>
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          {{ $t('advertising.unsubscribe_spa.error_invalid_or_expired') }}
        </p>
        <p class="text-xs text-surface-500">
          {{ $t('advertising.unsubscribe_spa.error_login_hint') }}
        </p>
      </div>

      <!-- エラー画面: レート制限 -->
      <div
        v-else-if="errorKind === 'RATE_LIMITED'"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-clock mb-4 text-4xl text-yellow-500" />
        <h1 class="mb-3 text-xl font-bold">
          {{ $t('advertising.unsubscribe_spa.error_title') }}
        </h1>
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ $t('advertising.unsubscribe_spa.error_rate_limited') }}
        </p>
      </div>

      <!-- エラー画面: その他 -->
      <div
        v-else-if="errorKind === 'UNKNOWN'"
        class="rounded-lg border border-surface-200 bg-white p-8 text-center dark:border-surface-700 dark:bg-surface-800"
      >
        <i class="pi pi-times-circle mb-4 text-4xl text-red-400" />
        <h1 class="mb-3 text-xl font-bold">
          {{ $t('advertising.unsubscribe_spa.error_title') }}
        </h1>
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ $t('advertising.unsubscribe_spa.error_unknown') }}
        </p>
      </div>

      <!-- メイン: チャネル選択 -->
      <div
        v-else
        class="rounded-lg border border-surface-200 bg-white p-8 dark:border-surface-700 dark:bg-surface-800"
      >
        <h1 class="mb-3 text-xl font-bold">
          {{ $t('advertising.unsubscribe_spa.title') }}
        </h1>
        <p class="mb-6 text-sm text-surface-600 dark:text-surface-300">
          {{ $t('advertising.unsubscribe_spa.description') }}
        </p>

        <div class="mb-6 flex flex-col gap-3">
          <label
            v-for="channel in ALL_CHANNELS"
            :key="channel"
            class="flex items-center gap-3 rounded border border-surface-200 px-3 py-2 dark:border-surface-700"
          >
            <input
              v-model="selected[channel]"
              type="checkbox"
              :data-testid="`unsubscribe-channel-${channel}`"
              class="h-4 w-4"
            >
            <span class="text-sm">{{ channelLabel(channel) }}</span>
          </label>
        </div>

        <Button
          :label="$t('advertising.unsubscribe_spa.submit_button')"
          :disabled="!canSubmit"
          :loading="submitting"
          severity="danger"
          class="w-full"
          data-testid="unsubscribe-submit"
          @click="onSubmit"
        />

        <p class="mt-4 text-xs text-surface-500">
          {{ $t('advertising.unsubscribe_spa.note') }}
        </p>
      </div>
    </div>
  </div>
</template>
