<script setup lang="ts">
import { useToast } from 'primevue/usetoast'
import type { ToastMessageOptions } from 'primevue/toast'
import type { UndoToastData } from '~/composables/useUndoToast'

/**
 * グローバル {@code <Toast>}（app.vue）の {@code #message} スロットとして使う共通テンプレート。
 *
 * <p>PrimeVue の Toast はメッセージ本体にアクションボタンを持たないため、{@code #message}
 * スロットでレンダリングを差し替え、{@code message.data.undoAction} を持つメッセージにだけ
 * 「元に戻す」ボタンを描画する（{@link useUndoToast} と対）。</p>
 *
 * <p>通常の Toast（{@code data.undoAction} 無し）は PrimeVue デフォルトと同等の
 * アイコン + summary + detail レイアウトを忠実に再現するため、既存 Toast の見た目・挙動は
 * 変わらない。閉じるボタン（closeButton）はスロット外で PrimeVue 本体が描画するため
 * ここには含めない。</p>
 */

const props = defineProps<{
  message: ToastMessageOptions
}>()

const toast = useToast()

const SEVERITY_ICON: Record<string, string> = {
  success: 'pi pi-check-circle',
  info: 'pi pi-info-circle',
  warn: 'pi pi-exclamation-triangle',
  error: 'pi pi-times-circle',
}

const undoData = computed<UndoToastData | null>(() => {
  const data = props.message.data as UndoToastData | undefined
  return data?.undoAction ? data : null
})

const iconClass = computed(() => SEVERITY_ICON[props.message.severity ?? 'info'] ?? SEVERITY_ICON.info)

async function onUndoClick() {
  const data = undoData.value
  // 先に Toast を閉じてからコールバックを発火する（多重押下防止）
  toast.remove(props.message)
  if (data) {
    await data.onUndo()
  }
}
</script>

<template>
  <div class="flex w-full items-start gap-3">
    <i :class="iconClass" class="mt-0.5 text-lg" aria-hidden="true" />
    <div class="flex min-w-0 flex-1 flex-col gap-1">
      <span class="font-semibold">{{ message.summary }}</span>
      <div v-if="message.detail" class="text-sm opacity-90">{{ message.detail }}</div>
      <button
        v-if="undoData"
        type="button"
        data-testid="undo-toast-button"
        class="mt-1 self-start rounded-md border border-current px-3 py-1 text-sm font-medium underline-offset-2 transition-opacity hover:opacity-80"
        @click="onUndoClick"
      >
        <i class="pi pi-undo mr-1 text-xs" aria-hidden="true" />
        {{ undoData.undoLabel }}
      </button>
    </div>
  </div>
</template>
