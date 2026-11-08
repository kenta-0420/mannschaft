<script setup lang="ts">
import QrCodeImage from '~/components/qr/QrCodeImage.vue'

/**
 * 秘密トークン（招待リンク・共有リンク・購読 URL など）を<b>発行した直後だけ</b>見せる共通ダイアログ（CMP-260826-1457）。
 *
 * <p>平文はサーバーにハッシュしか残らないため、発行応答で受け取った URL をここで一度だけ表示する。
 * 閉じたら二度と表示できないことを利用者に明示する。各波の一覧画面は平文・URL・QR を一覧に出さず、
 * 発行直後にこのダイアログだけを開く。</p>
 *
 * <p>平文の URL は localStorage・sessionStorage・Pinia などの保存領域に書かない（画面のメモリ上だけで扱う）。</p>
 */
const props = defineProps<{
  /** 発行応答で受け取った、平文入りの URL。 */
  url: string
  /** ダイアログ見出し。省略時は共通の「リンクを発行しました」。 */
  title?: string
}>()

const visible = defineModel<boolean>('visible', { default: false })

const { t } = useI18n()

/** コピー完了の表示を戻すまでの時間（ミリ秒）。 */
const COPIED_RESET_MS = 2000

const copied = ref(false)
const copyFailed = ref(false)
let resetTimer: ReturnType<typeof setTimeout> | null = null

const heading = computed(() => props.title ?? t('secret_token.issued_dialog.title'))

async function onCopy() {
  copyFailed.value = false
  try {
    await navigator.clipboard.writeText(props.url)
    copied.value = true
    if (resetTimer) clearTimeout(resetTimer)
    resetTimer = setTimeout(() => {
      copied.value = false
    }, COPIED_RESET_MS)
  }
  catch {
    copied.value = false
    copyFailed.value = true
  }
}

function onClose() {
  visible.value = false
}

// 閉じたら表示状態を初期化する（再び開いたときに前回のコピー表示を引きずらない）
watch(visible, (open) => {
  if (!open) {
    copied.value = false
    copyFailed.value = false
  }
})

onBeforeUnmount(() => {
  if (resetTimer) clearTimeout(resetTimer)
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="heading"
    class="w-full max-w-lg"
    data-testid="secret-issued-dialog"
  >
    <div class="flex flex-col gap-4">
      <p
        class="rounded-md bg-amber-50 p-3 text-sm leading-relaxed text-amber-900 dark:bg-amber-950 dark:text-amber-100"
        role="alert"
        data-testid="secret-issued-dialog-warning"
      >
        {{ t('secret_token.issued_dialog.once_warning') }}
      </p>

      <div class="flex flex-col gap-1">
        <span class="text-xs font-medium text-surface-500 dark:text-surface-400">
          {{ t('secret_token.issued_dialog.url_label') }}
        </span>
        <code
          class="block break-all rounded-md bg-surface-100 p-3 text-sm select-all dark:bg-surface-800"
          data-testid="secret-issued-dialog-url"
        >{{ url }}</code>
        <p
          v-if="copyFailed"
          class="text-xs text-red-600 dark:text-red-400"
          data-testid="secret-issued-dialog-copy-failed"
        >
          {{ t('secret_token.issued_dialog.copy_failed') }}
        </p>
      </div>

      <div class="flex flex-col items-center gap-1">
        <span class="text-xs font-medium text-surface-500 dark:text-surface-400">
          {{ t('secret_token.issued_dialog.qr_label') }}
        </span>
        <QrCodeImage :value="url" :size="200" />
      </div>

      <!-- 発行直後の追加操作（QR-PDF の保存など）は各波の画面が差し込む -->
      <slot name="actions" />
    </div>

    <template #footer>
      <Button
        :label="copied ? t('secret_token.issued_dialog.copied') : t('secret_token.issued_dialog.copy')"
        :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
        outlined
        data-testid="secret-issued-dialog-copy"
        @click="onCopy"
      />
      <Button
        :label="t('secret_token.issued_dialog.close')"
        icon="pi pi-times"
        data-testid="secret-issued-dialog-close"
        @click="onClose"
      />
    </template>
  </Dialog>
</template>
