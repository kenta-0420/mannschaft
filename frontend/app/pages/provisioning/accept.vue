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
 * 未ログイン時は `middleware: 'auth'` が `/login?redirect=<fullPath>` へ誘導する
 * （Vue Router は URL のハッシュ部分も `fullPath` に含めるため、フラグメント付きの
 * まま遷移し、ログイン後 `login.vue` が `redirect` へ戻す＝この画面へ再訪する）。
 */
definePageMeta({
  middleware: 'auth',
  layout: 'auth',
})

const { t } = useI18n()
const notification = useNotification()
const { formatDateTime } = useDatetime()

type ViewState = 'loading' | 'preview' | 'accepted' | 'error'

const state = ref<ViewState>('loading')
const token = ref<string | null>(null)
const preview = ref<{
  teamId?: number | null
  organizationId?: number | null
  scopeName?: string | null
  inviteEmail?: string | null
  expiresAt?: string | null
} | null>(null)
const acceptedResult = ref<{
  teamId?: number | null
  organizationId?: number | null
  scopeName?: string | null
} | null>(null)
const accepting = ref(false)

/** preview/accept 失敗時に表示するエラー種別（BE エラーコードから写像）。 */
type ErrorKind = 'notFound' | 'expired' | 'cancelled' | 'emailMismatch' | 'generic'
const errorKind = ref<ErrorKind>('generic')

const provisioningApi = useProvisioningInvitationApi()

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

/** BE エラーコード → 表示エラー種別のマッピング。 */
function classifyError(err: unknown): ErrorKind {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  if (code === 'PROV_002') return 'expired'
  if (code === 'PROV_003' || code === 'PROV_011') return 'cancelled'
  if (code === 'PROV_006') return 'emailMismatch'
  // PROV_001（見つからない）/ PROV_009（対象秘匿込みの一律404）は同じ「見つからない」表示に畳む。
  if (code === 'PROV_001' || code === 'PROV_009') return 'notFound'
  return 'generic'
}

async function loadPreview() {
  const tok = readTokenFromHash()
  token.value = tok
  if (!tok) {
    state.value = 'error'
    errorKind.value = 'notFound'
    return
  }

  try {
    const res = await provisioningApi.preview(tok)
    preview.value = res
    state.value = 'preview'
  } catch (err) {
    // BE の preview() は存在秘匿（AC1）のため、PENDING以外（ACCEPTED/CANCELLED/EXPIRED/
    // 存在しない）を一律 PROV_001 に畳んで返す。期限切れ/取消/本人による再承諾といった
    // 実際の理由は accept() 側でのみ区別される（PROV_002/PROV_003・PROV_011/PROV_010）ため、
    // preview が失敗した場合は accept を試みて実際の状態を判定する。
    // accept() は状態遷移前に必ず現在状態を検査するため（AC6悲観ロック/AC8/AC9）、
    // 既に PENDING でない招待に対する本呼び出しが誤って新規承諾を成立させることはない。
    console.error('provisioning/accept.vue: preview failed, falling back to accept for detail', err)
    await resolveViaAccept(tok)
  }
}

/**
 * preview() が存在秘匿のため理由を返さない場合に、accept() を試みて実際の状態
 * （期限切れ/取消済み/本人による再承諾=冪等成功/メール不一致/真に存在しない）を判定する。
 */
async function resolveViaAccept(tok: string) {
  try {
    const res = await provisioningApi.accept(tok)
    acceptedResult.value = res
    state.value = 'accepted'
  } catch (err) {
    const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'PROV_010') {
      // 招待は既に承諾済みだが、承諾者本人の再訪である。エラーにせず冪等成功として扱う。
      acceptedResult.value = null
      state.value = 'accepted'
      return
    }
    console.error('provisioning/accept.vue: accept fallback failed', err)
    state.value = 'error'
    errorKind.value = classifyError(err)
  }
}

async function accept() {
  if (!token.value || accepting.value) return
  accepting.value = true
  try {
    await resolveViaAccept(token.value)
    if (state.value === 'accepted') {
      notification.success(t('provisioning.accept.acceptSuccess'))
    }
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

      <!-- 承諾成功（新規 / 冪等） -->
      <div v-else-if="state === 'accepted'" class="text-center">
        <i class="pi pi-check-circle mb-4 text-5xl text-green-500" aria-hidden="true" />
        <h2 class="mb-2 text-lg font-bold">
          {{ acceptedResult?.scopeName ?? t('provisioning.accept.alreadyAcceptedTitle') }}
        </h2>
        <p class="mb-6 text-sm text-surface-500">
          {{ t('provisioning.accept.alreadyAcceptedMessage') }}
        </p>
        <Button
          :label="t('provisioning.accept.backToDashboard')"
          icon="pi pi-home"
          class="w-full"
          @click="navigateTo('/dashboard')"
        />
      </div>

      <!-- エラー -->
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
