<script setup lang="ts">
/**
 * 柱②-2 販促プロビジョニング: ADMIN 招待の承諾画面（承諾者側・要ログイン）。
 *
 * 金型: `pages/invite/[token].vue`（招待プレビュー→ログイン誘導→承諾）。
 *
 * トークンは URL の**フラグメント**（`#token=...`）に格納される（BE 確定契約。
 * `ProvisioningEmailEventListener` 参照）。フラグメントはサーバーへ送信されないため
 * アクセスログ・Referer に載らない。`window.location.hash` はクライアントでしか
 * 読めないため、SSR では何も描画せず `onMounted` でのみ読み取る。
 *
 * ログイン往復でのトークン漏出対策（検分 P1）: `redirect` クエリにフラグメントを
 * 含めると、ブラウザ履歴・Referer・（環境次第で）アクセスログにトークンが残りうる。
 * そのため、未ログイン時はカスタム inline middleware でフラグメントのトークンを
 * `sessionStorage`（{@link TOKEN_STORAGE_KEY}）へ退避してから、`frontend/app/middleware/auth.ts`
 * の `redirect` クエリ復帰パターンに合わせて `/login?redirect=%2Fprovisioning%2Faccept`
 * （トークンを含まない）へ遷移し、ログイン画面へ送る。
 * ログイン後の復帰時は、フラグメントにトークンが無ければ sessionStorage から復元する。
 * 復元したトークンは、accept 成功時（承諾フローが完全に終わったタイミング）まで
 * sessionStorage に残す（検分 P2-1: preview 失敗後の再試行でも同じトークンで
 * resolveToken() できるようにするため）。
 */
import type {
  ProvisioningInvitationAcceptResponse,
  ProvisioningInvitationPreviewResponse,
} from '~/composables/useProvisioningInvitationApi'
import {
  PROVISIONING_ACCEPT_TOKEN_STORAGE_KEY as TOKEN_STORAGE_KEY,
  buildProvisioningAcceptLoginRedirect,
} from '~/composables/useProvisioningAcceptAuthGuard'

/** URL フラグメント（#token=...）から平文トークンを読み取る。クライアントのみで有効。 */
function readTokenFromHash(): string | null {
  if (typeof window === 'undefined') return null
  const hash = window.location.hash
  if (!hash) return null
  const match = /(?:^#|&)token=([^&]+)/.exec(hash)
  if (!match) return null
  try {
    return decodeURIComponent(match[1] ?? '')
  } catch {
    return match[1] ?? null
  }
}

definePageMeta({
  layout: 'auth',
  middleware: [
    () => {
      if (import.meta.server) return
      const authStore = useAuthStore()
      if (authStore.isAuthenticated) return

      // 未ログイン: フラグメントにトークンがあれば sessionStorage へ退避してから、
      // トークンを含まない redirect クエリでログイン画面へ誘導する
      // （`frontend/app/middleware/auth.ts` の redirect クエリ復帰パターンに整合。
      // 検分 P1 再発防止: 自ページへ遷移させて留まるのではなく、必ず /login へ送る）。
      return navigateTo(buildProvisioningAcceptLoginRedirect(readTokenFromHash()))
    },
  ],
})

const { t } = useI18n()
const notification = useNotification()
const { formatDateTime } = useDatetime()

type ViewState = 'loading' | 'preview' | 'previewError' | 'accepted' | 'error'

const state = ref<ViewState>('loading')
const token = ref<string | null>(null)
const preview = ref<ProvisioningInvitationPreviewResponse | null>(null)
const acceptedResult = ref<ProvisioningInvitationAcceptResponse | null>(null)
const accepting = ref(false)

/** preview/accept 失敗時に表示するエラー種別（BE エラーコードから写像）。 */
type ErrorKind = 'notFound' | 'expired' | 'cancelled' | 'emailMismatch' | 'generic'
const errorKind = ref<ErrorKind>('generic')

const provisioningApi = useProvisioningInvitationApi()

/** BE エラーコード → 表示エラー種別のマッピング（accept() 応答にのみ適用）。 */
function classifyError(err: unknown): ErrorKind {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  if (code === 'PROV_002') return 'expired'
  if (code === 'PROV_003' || code === 'PROV_011') return 'cancelled'
  if (code === 'PROV_006') return 'emailMismatch'
  // PROV_001（見つからない）/ PROV_009（対象秘匿込みの一律404）/ PROV_010（承諾者本人以外に
  // よる再承諾＝存在秘匿のため本人以外には見つからない扱いに畳む）は同じ「見つからない」表示。
  if (code === 'PROV_001' || code === 'PROV_009' || code === 'PROV_010') return 'notFound'
  return 'generic'
}

/**
 * トークンを解決する（フラグメント優先、無ければログイン往復で退避した sessionStorage
 * から復元）。
 *
 * 検分 P2-1 根治: 復元直後に sessionStorage から削除すると、preview 失敗→再試行
 * （再読み込み）時にトークンが既に消えて resolveToken() が失敗する。削除は
 * accept 成功時（{@link clearStoredToken}）まで行わない。
 *
 * 検分 P2-2 根治: フラグメントにトークンがある場合は、古いトークンの残留を防ぐため
 * sessionStorage の値をこの新しい値で上書きする。
 */
function resolveToken(): string | null {
  const fromHash = readTokenFromHash()
  if (fromHash) {
    if (typeof window !== 'undefined') {
      try {
        window.sessionStorage.setItem(TOKEN_STORAGE_KEY, fromHash)
      } catch {
        // sessionStorage が使えない環境でも致命的ではない（フラグメントの値はそのまま使う）。
      }
    }
    return fromHash
  }

  if (typeof window === 'undefined') return null
  try {
    const stored = window.sessionStorage.getItem(TOKEN_STORAGE_KEY)
    if (stored) return stored
  } catch {
    // sessionStorage 不可時は復元できない（notFound へ畳む）。
  }
  return null
}

/** accept 成功など、承諾フローが完全に終わったタイミングで退避トークンを破棄する。 */
function clearStoredToken(): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
  } catch {
    // sessionStorage 不可時は何もしない。
  }
}

/**
 * 招待の下見を読み込む。
 *
 * 検分 P0 根治: preview 失敗時に accept() を自動発火するフォールバックは廃止した
 * （旧実装は preview の 500・ネットワーク断も含め、理由を問わず accept を自動で
 * 呼んでしまい、トークンの有効性が preview で確認できていない状態でも accept が
 * 発火しうる欠陥があった）。エラー種別の判別は、ユーザーが承諾ボタンを押した際の
 * accept() 応答でのみ行う。preview 失敗時は再試行可能なエラー表示のみを出す。
 */
async function loadPreview() {
  const tok = resolveToken()
  token.value = tok
  if (!tok) {
    state.value = 'error'
    errorKind.value = 'notFound'
    return
  }

  state.value = 'loading'
  try {
    const res = await provisioningApi.preview(tok)
    preview.value = res
    state.value = 'preview'
  } catch (err) {
    console.error('provisioning/accept.vue: preview failed', err)
    state.value = 'previewError'
  }
}

async function accept() {
  if (!token.value || accepting.value) return
  accepting.value = true
  try {
    const res = await provisioningApi.accept(token.value)
    acceptedResult.value = res
    state.value = 'accepted'
    clearStoredToken()
    notification.success(t('provisioning.accept.acceptSuccess'))
  } catch (err) {
    console.error('provisioning/accept.vue: accept failed', err)
    state.value = 'error'
    errorKind.value = classifyError(err)
  } finally {
    accepting.value = false
  }
}

function targetLabel(v: { teamId?: number | null; organizationId?: number | null } | null): string {
  if (!v) return ''
  if (v.teamId != null) return t('provisioning.accept.targetTeam')
  if (v.organizationId != null) return t('provisioning.accept.targetOrganization')
  return ''
}

onMounted(() => {
  loadPreview()
})
</script>

<template>
  <div class="flex min-h-screen items-center justify-center p-4">
    <div class="w-full max-w-md rounded-lg border p-8">
      <h1 class="mb-6 text-center text-xl font-bold">
        {{ t('provisioning.accept.title') }}
      </h1>

      <!-- ローディング -->
      <div v-if="state === 'loading'" class="flex flex-col items-center gap-3 py-8 text-surface-400">
        <i class="pi pi-spin pi-spinner text-3xl" aria-hidden="true" />
        <p class="text-sm">{{ t('provisioning.accept.loading') }}</p>
      </div>

      <!-- 招待プレビュー -->
      <div v-else-if="state === 'preview' && preview" class="text-center">
        <i class="pi pi-envelope mb-4 text-4xl text-primary-500" aria-hidden="true" />
        <p class="mb-1 text-sm text-surface-500">
          {{ targetLabel(preview) }}
        </p>
        <h2 class="mb-4 text-lg font-bold">
          {{ preview.scopeName }}
        </h2>

        <div class="mb-6 space-y-2 rounded-lg bg-surface-50 p-4 text-left text-sm dark:bg-surface-800">
          <div class="flex items-center justify-between">
            <span class="text-surface-500">{{ t('provisioning.accept.inviteEmailLabel') }}</span>
            <span class="font-medium">{{ preview.inviteEmail }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-surface-500">{{ t('provisioning.accept.expiresAtLabel') }}</span>
            <span class="font-medium">{{ preview.expiresAt ? formatDateTime(preview.expiresAt) : '-' }}</span>
          </div>
        </div>

        <Button
          :label="accepting ? t('provisioning.accept.accepting') : t('provisioning.accept.acceptBtn')"
          icon="pi pi-check"
          class="w-full"
          :loading="accepting"
          data-testid="provisioning-accept-button"
          @click="accept"
        />
      </div>

      <!-- preview 失敗（再試行可能） -->
      <div v-else-if="state === 'previewError'" class="text-center">
        <i class="pi pi-exclamation-triangle mb-4 text-5xl text-yellow-500" aria-hidden="true" />
        <h2 class="mb-2 text-lg font-bold">
          {{ t('provisioning.accept.previewError.title') }}
        </h2>
        <p class="mb-6 text-sm text-surface-500">
          {{ t('provisioning.accept.previewError.message') }}
        </p>
        <Button
          :label="t('provisioning.accept.previewError.retryBtn')"
          icon="pi pi-refresh"
          class="w-full"
          data-testid="provisioning-preview-retry-button"
          @click="loadPreview"
        />
      </div>

      <!-- 承諾成功 -->
      <div v-else-if="state === 'accepted'" class="text-center">
        <i class="pi pi-check-circle mb-4 text-5xl text-green-500" aria-hidden="true" />
        <h2 class="mb-2 text-lg font-bold">
          {{ acceptedResult?.scopeName ?? t('provisioning.accept.acceptedTitle') }}
        </h2>
        <p class="mb-6 text-sm text-surface-500">
          {{ t('provisioning.accept.acceptedMessage') }}
        </p>
        <Button
          :label="t('provisioning.accept.backToDashboard')"
          icon="pi pi-home"
          class="w-full"
          @click="navigateTo('/dashboard')"
        />
      </div>

      <!-- エラー（accept() 応答由来） -->
      <div v-else-if="state === 'error'" class="text-center">
        <i class="pi pi-exclamation-triangle mb-4 text-5xl text-yellow-500" aria-hidden="true" />
        <h2 class="mb-2 text-lg font-bold">
          {{ t(`provisioning.accept.errors.${errorKind}Title`) }}
        </h2>
        <p class="mb-6 text-sm text-surface-500">
          {{ t(`provisioning.accept.errors.${errorKind}Message`) }}
        </p>
        <Button
          :label="t('provisioning.accept.backToDashboard')"
          icon="pi pi-home"
          severity="secondary"
          class="w-full"
          @click="navigateTo('/dashboard')"
        />
      </div>
    </div>
  </div>
</template>
