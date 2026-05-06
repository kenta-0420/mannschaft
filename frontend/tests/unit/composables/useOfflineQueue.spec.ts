import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useOfflineQueue } from '~/composables/useOfflineQueue'
import type { CreateActionMemoPayload } from '~/types/actionMemo'

/**
 * F02.5 useOfflineQueue のユニットテスト（Phase 2）。
 *
 * - enqueue / getAll / remove / count の基本動作
 * - flushQueue が sender を順次呼び、成功した項目のみキューから消える
 * - sender が null を返したら以降の flush を中断（次回リトライに回す）
 *
 * <p>テスト環境では IndexedDB が存在しないため useOfflineQueue はインメモリ
 * フォールバックに切り替わる。タブを閉じると消える仕様だが、テスト中は問題ない。</p>
 */

function samplePayload(content: string): CreateActionMemoPayload {
  return { content, memoDate: '2026-04-09', mood: null }
}

beforeEach(async () => {
  const queue = useOfflineQueue()
  await queue.clearAll()
})

describe('useOfflineQueue', () => {
  it('enqueue でキューに積める / getAll で取り出せる', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('foo'), -1)
    await queue.enqueue(samplePayload('bar'), -2)
    const all = await queue.getAll()
    expect(all.length).toBe(2)
    expect(all[0]?.payload.content).toBe('foo')
    expect(all[1]?.payload.content).toBe('bar')
  })

  it('count がキュー件数を返す', async () => {
    const queue = useOfflineQueue()
    expect(await queue.count()).toBe(0)
    await queue.enqueue(samplePayload('foo'), -1)
    expect(await queue.count()).toBe(1)
    await queue.enqueue(samplePayload('bar'), -2)
    expect(await queue.count()).toBe(2)
  })

  it('hasQueuedItems: 空 → false / 1件以上 → true', async () => {
    const queue = useOfflineQueue()
    expect(await queue.hasQueuedItems()).toBe(false)
    await queue.enqueue(samplePayload('foo'), -1)
    expect(await queue.hasQueuedItems()).toBe(true)
  })

  it('remove で個別削除できる', async () => {
    const queue = useOfflineQueue()
    const first = await queue.enqueue(samplePayload('foo'), -1)
    await queue.enqueue(samplePayload('bar'), -2)
    expect(first.queueId).toBeDefined()
    await queue.remove(first.queueId as number)
    const all = await queue.getAll()
    expect(all.length).toBe(1)
    expect(all[0]?.payload.content).toBe('bar')
  })

  it('flushQueue で sender が順次呼ばれ、成功した項目がキューから消える', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('a'), -1)
    await queue.enqueue(samplePayload('b'), -2)
    await queue.enqueue(samplePayload('c'), -3)

    let idCounter = 100
    const sender = vi.fn().mockImplementation(async () => ({ id: idCounter++ }))

    const results = await queue.flushQueue(sender)
    expect(sender).toHaveBeenCalledTimes(3)
    expect(results.length).toBe(3)
    expect(results[0]?.createdId).toBe(100)
    expect(results[1]?.createdId).toBe(101)
    expect(results[2]?.createdId).toBe(102)
    expect(await queue.count()).toBe(0)
  })

  it('flushQueue は sender が null を返したら以降の送信を中断する', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('a'), -1)
    await queue.enqueue(samplePayload('b'), -2)
    await queue.enqueue(samplePayload('c'), -3)

    let call = 0
    const sender = vi.fn().mockImplementation(async () => {
      call++
      if (call === 1) return { id: 200 }
      return null
    })

    const results = await queue.flushQueue(sender)
    // 1 件目は成功、2 件目で break
    expect(sender).toHaveBeenCalledTimes(2)
    expect(results.length).toBe(1)
    expect(results[0]?.createdId).toBe(200)
    // 1 件消費、残り 2 件はキューに残る
    expect(await queue.count()).toBe(2)
  })

  it('flushQueue は sender が例外を投げても以降の送信を中断する', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('a'), -1)
    await queue.enqueue(samplePayload('b'), -2)

    const sender = vi.fn().mockImplementation(async () => {
      throw new Error('network error')
    })

    const results = await queue.flushQueue(sender)
    expect(sender).toHaveBeenCalledTimes(1)
    expect(results.length).toBe(0)
    expect(await queue.count()).toBe(2)
  })

  // ─── 追加テスト +12件 ──────────────────────────────────────────────────────

  it('enqueue の順序が FIFO で保持される（getAll の並び順）', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('first'), -10)
    await queue.enqueue(samplePayload('second'), -11)
    await queue.enqueue(samplePayload('third'), -12)

    const all = await queue.getAll()
    expect(all.length).toBe(3)
    expect(all[0]?.payload.content).toBe('first')
    expect(all[1]?.payload.content).toBe('second')
    expect(all[2]?.payload.content).toBe('third')
  })

  it('clearAll 後に count が 0 になる', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('x'), -1)
    await queue.enqueue(samplePayload('y'), -2)
    expect(await queue.count()).toBe(2)

    await queue.clearAll()
    expect(await queue.count()).toBe(0)
  })

  it('clearAll 後に getAll が空配列を返す', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('a'), -1)
    await queue.clearAll()

    const all = await queue.getAll()
    expect(all).toEqual([])
  })

  it('clearAll 後に hasQueuedItems が false を返す', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('data'), -1)
    await queue.clearAll()

    expect(await queue.hasQueuedItems()).toBe(false)
  })

  it('remove で先頭アイテムを削除しても残りの順序が崩れない', async () => {
    const queue = useOfflineQueue()
    const first = await queue.enqueue(samplePayload('alpha'), -1)
    await queue.enqueue(samplePayload('beta'), -2)
    await queue.enqueue(samplePayload('gamma'), -3)

    await queue.remove(first.queueId as number)

    const all = await queue.getAll()
    expect(all.length).toBe(2)
    expect(all[0]?.payload.content).toBe('beta')
    expect(all[1]?.payload.content).toBe('gamma')
  })

  it('remove で末尾アイテムを削除できる', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('alpha'), -1)
    const last = await queue.enqueue(samplePayload('beta'), -2)

    await queue.remove(last.queueId as number)

    const all = await queue.getAll()
    expect(all.length).toBe(1)
    expect(all[0]?.payload.content).toBe('alpha')
  })

  it('enqueue で返る queueId が一意である', async () => {
    const queue = useOfflineQueue()
    const a = await queue.enqueue(samplePayload('a'), -1)
    const b = await queue.enqueue(samplePayload('b'), -2)
    const c = await queue.enqueue(samplePayload('c'), -3)

    const ids = [a.queueId, b.queueId, c.queueId]
    const uniqueIds = new Set(ids)
    expect(uniqueIds.size).toBe(3)
  })

  it('flushQueue で全件成功後のキューは空になる', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('x'), -1)
    await queue.enqueue(samplePayload('y'), -2)

    const sender = vi.fn().mockResolvedValue({ id: 999 })
    await queue.flushQueue(sender)

    expect(await queue.count()).toBe(0)
    expect(await queue.hasQueuedItems()).toBe(false)
  })

  it('flushQueue の sender に渡されるペイロードが enqueue 時と一致する', async () => {
    const queue = useOfflineQueue()
    const payload = samplePayload('check-payload')
    await queue.enqueue(payload, -1)

    const capturedPayloads: typeof payload[] = []
    const sender = vi.fn().mockImplementation(async (p: typeof payload) => {
      capturedPayloads.push(p)
      return { id: 1 }
    })

    await queue.flushQueue(sender)

    expect(capturedPayloads.length).toBe(1)
    expect(capturedPayloads[0]?.content).toBe('check-payload')
    expect(capturedPayloads[0]?.memoDate).toBe('2026-04-09')
  })

  it('flushQueue で sender が全件 null を返したらキューが全件残る', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('a'), -1)
    await queue.enqueue(samplePayload('b'), -2)
    await queue.enqueue(samplePayload('c'), -3)

    const sender = vi.fn().mockResolvedValue(null)
    const results = await queue.flushQueue(sender)

    // null を返した時点で break するので sender は 1 回しか呼ばれない
    expect(sender).toHaveBeenCalledTimes(1)
    expect(results.length).toBe(0)
    expect(await queue.count()).toBe(3)
  })

  it('count は getAll の件数と一致する', async () => {
    const queue = useOfflineQueue()
    await queue.enqueue(samplePayload('p'), -1)
    await queue.enqueue(samplePayload('q'), -2)
    await queue.enqueue(samplePayload('r'), -3)

    const countVal = await queue.count()
    const allItems = await queue.getAll()

    expect(countVal).toBe(allItems.length)
  })

  it('mood フィールドが null のペイロードもエンキュー・デキューできる', async () => {
    const queue = useOfflineQueue()
    const payload = { content: 'no mood', memoDate: '2026-05-01', mood: null }
    await queue.enqueue(payload, -99)

    const all = await queue.getAll()
    expect(all.length).toBe(1)
    expect(all[0]?.payload.mood).toBeNull()
    expect(all[0]?.payload.content).toBe('no mood')
  })
})
