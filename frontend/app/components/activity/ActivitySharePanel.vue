<script setup lang="ts">
interface Props {
  activityId: number
  title: string
  /** 公開 URL（例: https://mannschaft.app/activity/123） */
  url: string
}

const props = defineProps<Props>()

// URLコピー後の一時的なフィードバック表示
const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

// Web Share API が使えるかどうか
const canNativeShare = computed(() => {
  if (typeof navigator === 'undefined') return false
  return typeof navigator.share === 'function' && navigator.canShare?.({ title: props.title, url: props.url })
})

/**
 * X (Twitter) でシェアする
 */
function shareToX() {
  const tweetText = encodeURIComponent(props.title)
  const tweetUrl = encodeURIComponent(props.url)
  window.open(`https://x.com/intent/tweet?text=${tweetText}&url=${tweetUrl}`, '_blank', 'noopener,noreferrer')
}

/**
 * Threads でシェアする
 */
function shareToThreads() {
  const text = encodeURIComponent(`${props.title} ${props.url}`)
  window.open(`https://www.threads.net/intent/post?text=${text}`, '_blank', 'noopener,noreferrer')
}

/**
 * LINE でシェアする
 */
function shareToLine() {
  const shareUrl = encodeURIComponent(props.url)
  window.open(`https://social-plugins.line.me/lineit/share?url=${shareUrl}`, '_blank', 'noopener,noreferrer')
}

/**
 * メールでシェアする
 */
function shareViaEmail() {
  const subject = encodeURIComponent(props.title)
  const body = encodeURIComponent(props.url)
  window.open(`mailto:?subject=${subject}&body=${body}`, '_blank')
}

/**
 * Web Share API でネイティブシェアを起動する（モバイル向け）
 */
async function nativeShare() {
  try {
    await navigator.share({ title: props.title, url: props.url })
  } catch {
    // ユーザーがキャンセルした場合は何もしない
  }
}

/**
 * URL をクリップボードにコピーし、1 秒間コピー完了フィードバックを表示する
 */
async function copyLink() {
  try {
    await navigator.clipboard.writeText(props.url)
    copied.value = true
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copied.value = false
    }, 1000)
  } catch {
    // clipboard API が使えない場合は何もしない
  }
}

onUnmounted(() => {
  if (copyTimer) clearTimeout(copyTimer)
})
</script>

<template>
  <div class="rounded-lg border border-surface-200 bg-white p-5 dark:border-surface-700 dark:bg-surface-800">
    <!-- タイトル -->
    <h2 class="mb-4 text-sm font-semibold text-surface-700 dark:text-surface-300">
      <i class="pi pi-share-alt mr-2" />{{ $t('share.shareVia') }}
    </h2>

    <div class="flex flex-wrap gap-2">
      <!-- X (Twitter) -->
      <Button
        :label="$t('share.x')"
        icon="pi pi-twitter"
        severity="secondary"
        outlined
        size="small"
        @click="shareToX"
      />

      <!-- Threads -->
      <Button
        :label="$t('share.threads')"
        severity="secondary"
        outlined
        size="small"
        @click="shareToThreads"
      >
        <template #icon>
          <!-- Threads アイコン（SVG） -->
          <svg
            class="mr-1 h-4 w-4"
            viewBox="0 0 192 192"
            fill="currentColor"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path
              d="M141.537 88.988a66.667 66.667 0 0 0-2.518-1.143c-1.482-27.307-16.403-42.94-41.457-43.1h-.34c-14.986 0-27.449 6.396-35.12 18.05l13.561 9.325c5.709-8.649 14.672-10.491 21.559-10.491h.232c8.347.055 14.633 2.474 18.69 7.195 2.973 3.438 4.97 8.201 5.96 14.173-7.421-1.261-15.455-1.648-24.07-1.155-24.19 1.394-39.737 15.546-38.734 35.219.503 9.967 5.476 18.553 14.003 24.155 7.163 4.7 16.384 6.995 25.963 6.49 12.646-.678 22.549-5.515 29.432-14.374 5.235-6.806 8.545-15.608 9.985-26.641 5.983 3.607 10.423 8.372 12.85 14.132 4.083 9.818 4.325 25.941-8.452 38.555-11.177 11.022-24.923 15.775-45.481 15.927-22.762-.169-39.976-7.47-51.158-21.7C35.496 127.731 29.968 109.895 29.748 87.2c.22-22.695 5.748-40.53 16.421-53.025 11.182-14.23 28.396-21.531 51.158-21.7 22.92.17 40.526 7.508 52.346 21.807 5.765 7.006 10.164 15.955 13.067 26.613l15.449-4.12c-3.466-12.913-9.014-23.834-16.552-32.612C147.647 8.047 125.948-.387 97.611-.2h-.113C69.31-.387 47.36 8.141 33.16 25.148 20.601 40.237 14.031 61.035 13.8 87.2v.4c.232 26.165 6.801 46.963 19.361 62.053C47.36 166.66 69.31 175.188 97.498 175h.113c25.254-.169 43.22-6.877 57.967-21.482 19.098-18.944 18.525-42.606 12.228-57.104-4.494-10.809-13.168-19.53-26.269-25.426Zm-45.76 40.084c-10.601.568-21.599-4.163-21.977-14.146-.272-7.066 5.034-14.94 21.33-15.888 1.866-.108 3.703-.162 5.513-.162 6.381 0 12.359.619 17.809 1.818-2.026 25.265-12.904 27.823-22.675 28.378Z"
            />
          </svg>
        </template>
      </Button>

      <!-- LINE -->
      <Button
        :label="$t('share.line')"
        severity="secondary"
        outlined
        size="small"
        @click="shareToLine"
      >
        <template #icon>
          <svg
            class="mr-1 h-4 w-4"
            viewBox="0 0 24 24"
            fill="currentColor"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path
              d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.064-.022.134-.033.199-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.281.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.070 9.436-6.975C23.176 14.393 24 12.458 24 10.314"
            />
          </svg>
        </template>
      </Button>

      <!-- メール -->
      <Button
        :label="$t('share.mail')"
        icon="pi pi-envelope"
        severity="secondary"
        outlined
        size="small"
        @click="shareViaEmail"
      />

      <!-- Web Share API（モバイル対応・使えない場合は非表示） -->
      <Button
        v-if="canNativeShare"
        :label="$t('share.openInApp')"
        icon="pi pi-external-link"
        severity="secondary"
        outlined
        size="small"
        @click="nativeShare"
      />

      <!-- URLコピー -->
      <Button
        :label="copied ? $t('share.copied') : $t('share.copyLink')"
        :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
        :severity="copied ? 'success' : 'secondary'"
        outlined
        size="small"
        @click="copyLink"
      />
    </div>
  </div>
</template>
