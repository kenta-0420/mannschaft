/**
 * ポイっとメモ入力モーダルの開閉管理 + キーボードショートカット登録。
 * グローバルに1つのモーダルを制御するため、app.vue 近辺で provide/inject するか、
 * このファイルをモジュールレベルで共有する。
 */

const _visible = ref(false)
const _initialText = ref('')

export function useQuickMemoCapture() {
  function open(initialText = '') {
    _initialText.value = initialText
    _visible.value = true
  }

  function close() {
    _visible.value = false
    _initialText.value = ''
  }

  // Ctrl+Shift+M / Cmd+Shift+M でモーダルを開く
  function registerShortcut() {
    if (typeof window === 'undefined') return

    function handleKeydown(e: KeyboardEvent) {
      const isMod = e.ctrlKey || e.metaKey
      if (isMod && e.shiftKey && e.key.toLowerCase() === 'm') {
        e.preventDefault()
        open()
      }
    }

    window.addEventListener('keydown', handleKeydown)
    onUnmounted(() => window.removeEventListener('keydown', handleKeydown))
  }

  return {
    visible: readonly(_visible),
    initialText: readonly(_initialText),
    open,
    close,
    registerShortcut,
  }
}
