import { ref } from 'vue'
export interface RanchCommandSnapshot { path: string; method: 'POST' | 'PUT' | 'DELETE'; body?: unknown; version?: string; key: string }
export function useRanchCommand() {
 const pending = ref<RanchCommandSnapshot | null>(null)
 const running = ref(false)
 async function execute<T>(input: Omit<RanchCommandSnapshot, 'key'>, send: (snapshot: RanchCommandSnapshot) => Promise<T>): Promise<T> {
  if (running.value) throw new Error('COMMAND_BUSY')
  const snapshot: RanchCommandSnapshot = pending.value ?? { ...JSON.parse(JSON.stringify(input)) as Omit<RanchCommandSnapshot, 'key'>, key: crypto.randomUUID() }
  if (JSON.stringify({ ...snapshot, key: undefined }) !== JSON.stringify({ ...input, key: undefined })) throw new Error('COMMAND_RETRY_REQUIRED')
  pending.value = snapshot; running.value = true
  try { const result = await send(snapshot); pending.value = null; return result } finally { running.value = false }
 }
 // 409/400等の確定失敗を画面で確認し再読込した後だけ破棄する。
 function discardRejected() { if (!running.value) pending.value = null }
 return { pending, running, execute, discardRejected }
}
