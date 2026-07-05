import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref } from 'vue'
import { useFormDraft } from '~/composables/useFormDraft'

/**
 * useFormDraft のユニットテスト。
 *
 * 受け入れ条件（AC）とテストの対応:
 * - AC-1: 監視対象が変化してから約1秒後（debounce）に localStorage へ保存される
 * - AC-2: clear() で下書き削除。保存失敗・未送信時は保持される
 * - AC-3: 初期化時（restore）に前回の下書きを復元できる（文字列・オブジェクト両対応）
 */

describe('useFormDraft', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('AC-1: draft 変更から debounce(1000ms) 経過後に localStorage へ保存される', () => {
    const key = 'todo-create-draft-42'
    const { draft } = useFormDraft<string>(key, { debounceMs: 1000 })

    draft.value = '朝散歩'
    // debounce 前は未保存
    vi.advanceTimersByTime(999)
    expect(localStorage.getItem(key)).toBeNull()

    // debounce 経過で保存される（JSON 直列化される）
    vi.advanceTimersByTime(1)
    expect(localStorage.getItem(key)).toBe(JSON.stringify('朝散歩'))
  })

  it('AC-1: 連続変更では最後の値だけが保存される（debounce リセット）', () => {
    const key = 'todo-create-draft-42'
    const { draft } = useFormDraft<string>(key, { debounceMs: 1000 })

    draft.value = 'あ'
    vi.advanceTimersByTime(500)
    draft.value = 'あい'
    vi.advanceTimersByTime(500)
    // まだ 1000ms 経っていない（2回目の変更から 500ms）
    expect(localStorage.getItem(key)).toBeNull()

    vi.advanceTimersByTime(500)
    expect(localStorage.getItem(key)).toBe(JSON.stringify('あい'))
  })

  it('AC-1: オブジェクト（reactive フォーム）も JSON 直列化して保存できる', () => {
    const key = 'survey-draft-7'
    const form = ref<{ title: string; body: string }>({ title: '', body: '' })
    useFormDraft<{ title: string; body: string }>(key, { source: form, debounceMs: 1000 })

    form.value.title = '週次ふりかえり'
    vi.advanceTimersByTime(1000)
    expect(localStorage.getItem(key)).toBe(
      JSON.stringify({ title: '週次ふりかえり', body: '' }),
    )
  })

  it('AC-2: clear() で下書きが削除される', () => {
    const key = 'todo-create-draft-42'
    const { draft, clear } = useFormDraft<string>(key, { debounceMs: 1000 })

    draft.value = '削除される予定'
    vi.advanceTimersByTime(1000)
    expect(localStorage.getItem(key)).not.toBeNull()

    clear()
    expect(localStorage.getItem(key)).toBeNull()
  })

  it('AC-2: 未送信（clear を呼ばない）なら下書きは保持される', () => {
    const key = 'todo-create-draft-42'
    const { draft } = useFormDraft<string>(key, { debounceMs: 1000 })

    draft.value = '書きかけ'
    vi.advanceTimersByTime(1000)
    // clear を呼ばない限り残る
    expect(localStorage.getItem(key)).toBe(JSON.stringify('書きかけ'))
  })

  it('AC-2: 空文字/空オブジェクトは保存対象外（削除される）', () => {
    const key = 'todo-create-draft-42'
    const { draft } = useFormDraft<string>(key, { debounceMs: 1000 })

    // 先に何か保存しておく
    draft.value = 'x'
    vi.advanceTimersByTime(1000)
    expect(localStorage.getItem(key)).not.toBeNull()

    // 空にすると削除される
    draft.value = ''
    vi.advanceTimersByTime(1000)
    expect(localStorage.getItem(key)).toBeNull()
  })

  it('AC-3: restore() で前回保存した文字列の下書きを復元できる', () => {
    const key = 'todo-create-draft-42'
    localStorage.setItem(key, JSON.stringify('復元されるべき下書き'))

    const { draft, restore } = useFormDraft<string>(key, { debounceMs: 1000 })
    // draft モードでは初期化時に autoRestore で draft へ反映される
    expect(draft.value).toBe('復元されるべき下書き')
    // 明示 restore() も同じ値を返す
    expect(restore()).toBe('復元されるべき下書き')
  })

  it('AC-3: restore() でオブジェクトの下書きを復元できる', () => {
    const key = 'survey-draft-7'
    const stored = { title: '保存済みタイトル', body: '本文' }
    localStorage.setItem(key, JSON.stringify(stored))

    const form = ref<{ title: string; body: string }>({ title: '', body: '' })
    const { restore } = useFormDraft<{ title: string; body: string }>(key, {
      source: form,
      debounceMs: 1000,
    })
    // source を渡すモードでは呼び出し側が restore() の戻り値を流し込む想定
    expect(restore()).toEqual(stored)
  })

  it('AC-3: 下書きが無ければ restore() は null を返す', () => {
    const { restore } = useFormDraft<string>('empty-key', { debounceMs: 1000 })
    expect(restore()).toBeNull()
  })

  it('savedFlash: 保存時に true になり flashMs 後に false へ戻る', () => {
    const key = 'todo-create-draft-42'
    const { draft, savedFlash } = useFormDraft<string>(key, {
      debounceMs: 1000,
      flashMs: 1500,
    })

    draft.value = 'フラッシュ確認'
    vi.advanceTimersByTime(1000)
    expect(savedFlash.value).toBe(true)

    vi.advanceTimersByTime(1500)
    expect(savedFlash.value).toBe(false)
  })
})
