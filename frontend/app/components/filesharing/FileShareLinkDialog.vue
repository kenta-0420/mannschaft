<script setup lang="ts">
import type { SharedFile, PublicFileLink } from '~/types/filesharing'

/**
 * F05.5 (D) 公開共有リンクの発行・一覧・失効ダイアログ。
 * 発行は BE 側で管理者/所有者に限定されており、権限が無い場合は 403 を丁寧に案内する。
 */
const props = defineProps<{ file: SharedFile }>()
const visible = defineModel<boolean>('visible', { default: false })

const { getFileLinks, createFileLink, deleteFileLink } = useFileSharingApi()
const { showSuccess, showError } = useNotification()
const { formatDateTime } = useDatetime()
const { t } = useI18n()

const links = ref<PublicFileLink[]>([])
const loading = ref(false)
const creating = ref(false)
const forbidden = ref(false)

// 発行フォーム
const expiresAt = ref<Date | null>(null)
const downloadAllowed = ref(false)
const password = ref('')

// 有効期限は「今」〜「30日先」まで
const minDate = new Date()
const maxDate = computed(() => {
  const d = new Date()
  d.setDate(d.getDate() + 30)
  return d
})

/** ofetch / useApi のエラーから HTTP ステータスを取り出す。 */
function statusOf(err: unknown): number | undefined {
  const e = err as { response?: { status?: number }, status?: number, statusCode?: number }
  return e?.response?.status ?? e?.status ?? e?.statusCode
}

/** Date を LocalDateTime 文字列（YYYY-MM-DDTHH:mm:ss・TZ なし）へ整形する。 */
function toLocalDateTime(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

function publicUrl(token: string): string {
  const origin = typeof window !== 'undefined' ? window.location.origin : ''
  return `${origin}/shared/${token}`
}

async function loadLinks() {
  loading.value = true
  forbidden.value = false
  try {
    const res = await getFileLinks(props.file.id)
    links.value = (res.data as unknown as PublicFileLink[]) ?? []
  }
  catch (err) {
    if (statusOf(err) === 403) forbidden.value = true
    else showError(t('file_sharing.publicLink.loadError'))
  }
  finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!expiresAt.value) {
    showError(t('file_sharing.publicLink.errors.expiryInvalid'))
    return
  }
  creating.value = true
  try {
    await createFileLink(props.file.id, {
      expiresAt: toLocalDateTime(expiresAt.value),
      downloadAllowed: downloadAllowed.value,
      password: password.value.trim() || undefined,
    })
    showSuccess(t('file_sharing.publicLink.created'))
    // フォームをリセットして一覧を再取得
    expiresAt.value = null
    downloadAllowed.value = false
    password.value = ''
    await loadLinks()
  }
  catch (err) {
    const status = statusOf(err)
    if (status === 403) {
      forbidden.value = true
      showError(t('file_sharing.publicLink.adminOnly'))
    }
    else if (status === 400) showError(t('file_sharing.publicLink.errors.expiryInvalid'))
    else showError(t('file_sharing.publicLink.createError'))
  }
  finally {
    creating.value = false
  }
}

async function onCopy(token: string) {
  try {
    await navigator.clipboard.writeText(publicUrl(token))
    showSuccess(t('file_sharing.publicLink.copied'))
  }
  catch {
    showError(t('file_sharing.publicLink.copyError'))
  }
}

async function onDelete(link: PublicFileLink) {
  try {
    await deleteFileLink(props.file.id, link.id)
    links.value = links.value.filter(l => l.id !== link.id)
    showSuccess(t('file_sharing.publicLink.revoked'))
  }
  catch {
    showError(t('file_sharing.publicLink.revokeError'))
  }
}

// ダイアログが開かれるたびに最新のリンク一覧を読み込む
watch(visible, (open) => {
  if (open) loadLinks()
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('file_sharing.publicLink.title')"
    class="w-full max-w-lg"
    data-testid="file-share-link-dialog"
  >
    <div class="flex flex-col gap-5">
      <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
        {{ t('file_sharing.publicLink.description') }}
      </p>

      <!-- 権限が無い場合の案内 -->
      <Message v-if="forbidden" severity="warn" :closable="false">
        {{ t('file_sharing.publicLink.adminOnly') }}
      </Message>

      <template v-else>
        <!-- 発行フォーム -->
        <SectionCard>
          <div class="flex flex-col gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('file_sharing.publicLink.expiresLabel') }}
              </label>
              <DatePicker
                v-model="expiresAt"
                class="w-full"
                show-time
                hour-format="24"
                date-format="yy/mm/dd"
                show-icon
                :min-date="minDate"
                :max-date="maxDate"
                :placeholder="t('file_sharing.publicLink.expiresPlaceholder')"
                data-testid="share-link-expires"
              />
              <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
                {{ t('file_sharing.publicLink.expiresHelp') }}
              </p>
            </div>

            <div>
              <div class="flex items-center justify-between gap-3">
                <label class="text-sm font-medium">
                  {{ t('file_sharing.publicLink.downloadAllowedLabel') }}
                </label>
                <ToggleSwitch v-model="downloadAllowed" data-testid="share-link-download-allowed" />
              </div>
              <p class="mt-1 text-xs text-surface-500 dark:text-surface-400">
                {{ t('file_sharing.publicLink.downloadAllowedHelp') }}
              </p>
            </div>

            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('file_sharing.publicLink.passwordLabel') }}
              </label>
              <Password
                v-model="password"
                class="w-full"
                input-class="w-full"
                :feedback="false"
                toggle-mask
                :placeholder="t('file_sharing.publicLink.passwordPlaceholder')"
                data-testid="share-link-password"
              />
            </div>

            <Button
              :label="t('file_sharing.publicLink.createButton')"
              icon="pi pi-link"
              :loading="creating"
              :disabled="!expiresAt"
              class="self-start"
              data-testid="share-link-create"
              @click="onCreate"
            />
          </div>
        </SectionCard>

        <!-- 既存リンク一覧 -->
        <div>
          <h3 class="mb-2 text-sm font-semibold">{{ t('file_sharing.publicLink.existingTitle') }}</h3>
          <div v-if="loading" class="flex justify-center py-4">
            <LoadingBounce />
          </div>
          <p
            v-else-if="links.length === 0"
            class="py-3 text-center text-sm text-surface-400"
          >
            {{ t('file_sharing.publicLink.empty') }}
          </p>
          <ul v-else class="flex flex-col gap-2">
            <li
              v-for="link in links"
              :key="link.id"
              class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
            >
              <div class="flex items-center gap-2">
                <code class="min-w-0 flex-1 truncate text-xs text-surface-600 dark:text-surface-300">
                  {{ publicUrl(link.token) }}
                </code>
                <Button
                  icon="pi pi-copy"
                  text
                  rounded
                  size="small"
                  :aria-label="t('file_sharing.publicLink.copyButton')"
                  @click="onCopy(link.token)"
                />
                <Button
                  icon="pi pi-trash"
                  text
                  rounded
                  size="small"
                  severity="danger"
                  :aria-label="t('file_sharing.publicLink.revokeButton')"
                  @click="onDelete(link)"
                />
              </div>
              <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-400">
                <span>
                  {{ t('file_sharing.publicLink.expiresAtLabel') }}:
                  {{ link.expiresAt ? formatDateTime(link.expiresAt) : '-' }}
                </span>
                <Tag
                  v-if="link.downloadAllowed"
                  :value="t('file_sharing.publicLink.tagDownload')"
                  severity="info"
                />
                <Tag
                  v-else
                  :value="t('file_sharing.publicLink.tagViewOnly')"
                  severity="secondary"
                />
                <Tag
                  v-if="link.hasPassword"
                  :value="t('file_sharing.publicLink.tagPassword')"
                  severity="warn"
                />
              </div>
            </li>
          </ul>
        </div>
      </template>
    </div>

    <template #footer>
      <Button :label="t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
