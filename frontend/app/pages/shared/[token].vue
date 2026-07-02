<script setup lang="ts">
import type { PublicSharedFileMeta } from '~/types/filesharing'

/**
 * F05.5 (D) 公開共有リンクの閲覧ページ（未認証）。
 *
 * このアプリは「middleware: 'auth' を付けたページのみログイン必須」の方式で、
 * 認証ミドルウェアを付けなければ未ログインで閲覧できる（public/teams/[slug] と同方針）。
 * よって本ページは auth ミドルウェアを付与しない。auth: false は「意図的に公開」であることの明示。
 * layout: false でアプリシェル（サイドバー等）を外し、外部の受信者向けに素の画面で表示する。
 */
definePageMeta({ auth: false, layout: false })

const route = useRoute()
const { accessLink, requestDownloadUrl } = usePublicFileShareApi()
const { formatDateTime } = useDatetime()
const { t } = useI18n()

const token = computed(() => String(route.params.token))

type ViewState = 'loading' | 'ready' | 'needPassword' | 'error'
const state = ref<ViewState>('loading')
const meta = ref<PublicSharedFileMeta | null>(null)
const password = ref('')
const passwordError = ref(false)
const errorKey = ref<string>('file_sharing.publicLink.errors.notFound')
const downloading = ref(false)
const submitting = ref(false)
const downloadNotice = ref(false)

// 検索エンジンにインデックスさせない（公開リンクの秘匿）
useHead({
  title: () => t('file_sharing.sharedPage.pageTitle'),
  meta: [{ name: 'robots', content: 'noindex, nofollow' }],
})

function statusOf(err: unknown): number | undefined {
  const e = err as { response?: { status?: number }, status?: number, statusCode?: number }
  return e?.response?.status ?? e?.status ?? e?.statusCode
}

/** アクセス結果のエラー status を画面状態へ写像する。passwordSubmitted で 403 の意味を切り替える。 */
function handleAccessError(err: unknown, passwordSubmitted: boolean) {
  const status = statusOf(err)
  if (status === 403) {
    // パスワード保護リンク。未入力の初回 403＝要パスワード、入力後 403＝パスワード誤り。
    state.value = 'needPassword'
    passwordError.value = passwordSubmitted
    return
  }
  state.value = 'error'
  if (status === 404) errorKey.value = 'file_sharing.publicLink.errors.notFound'
  else if (status === 410) errorKey.value = 'file_sharing.publicLink.errors.expired'
  else errorKey.value = 'file_sharing.publicLink.errors.notFound'
}

async function access(pwd?: string) {
  submitting.value = true
  try {
    const res = await accessLink(token.value, pwd)
    meta.value = res.data
    state.value = 'ready'
  }
  catch (err) {
    handleAccessError(err, pwd !== undefined)
  }
  finally {
    submitting.value = false
  }
}

async function onSubmitPassword() {
  if (!password.value.trim()) return
  await access(password.value)
}

async function onDownload() {
  downloading.value = true
  try {
    const res = await requestDownloadUrl(token.value, password.value || undefined)
    window.open(res.data.downloadUrl, '_blank', 'noopener')
  }
  catch (err) {
    const status = statusOf(err)
    if (status === 410) {
      state.value = 'error'
      errorKey.value = 'file_sharing.publicLink.errors.expired'
    }
    else if (status === 404) {
      state.value = 'error'
      errorKey.value = 'file_sharing.publicLink.errors.notFound'
    }
    else {
      // 403（DL 未許可 / C 禁止）はページ遷移せずトースト相当の注意を出す
      passwordError.value = false
      errorKey.value = 'file_sharing.publicLink.errors.downloadNotAllowed'
      downloadNotice.value = true
    }
  }
  finally {
    downloading.value = false
  }
}

function formatSize(bytes: number | undefined): string {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

onMounted(() => access())
</script>

<template>
  <div class="min-h-screen bg-surface-50 px-4 py-10 dark:bg-surface-950">
    <div class="mx-auto w-full max-w-lg">
      <PageHeader :title="t('file_sharing.sharedPage.title')" :back="false" size="sm" />

      <SectionCard>
        <!-- ローディング -->
        <div v-if="state === 'loading'" class="flex justify-center py-10">
          <LoadingBounce />
        </div>

        <!-- パスワード入力 -->
        <div v-else-if="state === 'needPassword'" class="flex flex-col gap-4 py-2">
          <div class="flex items-center gap-3">
            <i class="pi pi-lock text-2xl text-amber-500" aria-hidden="true" />
            <p class="text-sm text-surface-600 dark:text-surface-300">
              {{ t('file_sharing.sharedPage.passwordRequired') }}
            </p>
          </div>
          <Password
            v-model="password"
            class="w-full"
            input-class="w-full"
            :feedback="false"
            toggle-mask
            :placeholder="t('file_sharing.publicLink.passwordLabel')"
            data-testid="shared-password-input"
            @keyup.enter="onSubmitPassword"
          />
          <Message v-if="passwordError" severity="error" :closable="false">
            {{ t('file_sharing.publicLink.errors.passwordInvalid') }}
          </Message>
          <Button
            :label="t('file_sharing.sharedPage.unlockButton')"
            icon="pi pi-unlock"
            :loading="submitting"
            :disabled="!password.trim()"
            data-testid="shared-password-submit"
            @click="onSubmitPassword"
          />
        </div>

        <!-- エラー -->
        <div v-else-if="state === 'error'" class="flex flex-col items-center gap-3 py-10 text-center">
          <i class="pi pi-exclamation-triangle text-4xl text-surface-300" aria-hidden="true" />
          <p class="text-surface-600 dark:text-surface-300">{{ t(errorKey) }}</p>
        </div>

        <!-- ファイルメタ表示 -->
        <div v-else class="flex flex-col gap-5 py-2" data-testid="shared-file-meta">
          <div class="flex items-start gap-4">
            <i class="pi pi-file text-3xl text-primary" aria-hidden="true" />
            <div class="min-w-0 flex-1">
              <h2 class="break-words text-lg font-semibold">{{ meta?.name }}</h2>
              <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-400">
                <span v-if="meta?.fileSize != null">{{ formatSize(meta.fileSize) }}</span>
                <span v-if="meta?.contentType">{{ meta.contentType }}</span>
                <span v-if="meta?.updatedAt">{{ formatDateTime(meta.updatedAt) }}</span>
              </div>
            </div>
          </div>

          <p v-if="meta?.description" class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ meta.description }}
          </p>

          <Message v-if="downloadNotice" severity="warn" :closable="false">
            {{ t('file_sharing.publicLink.errors.downloadNotAllowed') }}
          </Message>

          <!-- ダウンロード（C 禁止ファイルはボタンを出さない） -->
          <Button
            v-if="!meta?.downloadDisabled"
            :label="t('file_sharing.sharedPage.downloadButton')"
            icon="pi pi-download"
            :loading="downloading"
            class="self-start"
            data-testid="shared-download"
            @click="onDownload"
          />
          <Message v-else severity="info" :closable="false">
            {{ t('file_sharing.sharedPage.viewOnly') }}
          </Message>
        </div>
      </SectionCard>

      <p class="mt-4 text-center text-xs text-surface-400">
        {{ t('file_sharing.sharedPage.footer') }}
      </p>
    </div>
  </div>
</template>
