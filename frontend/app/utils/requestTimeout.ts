export type RequestTimeoutResult<T> =
  | { status: 'success'; value: T }
  | { status: 'error'; error: unknown }
  | { status: 'timeout' }

export const STARTUP_REQUEST_TIMEOUT_MS = 15_000

export class RequestTimeoutError extends Error {
  constructor() {
    super('Request timed out')
    this.name = 'RequestTimeoutError'
  }
}

/**
 * UI の初期取得向けに、対象リクエストだけへ待機上限を付ける。
 *
 * タイムアウト時は AbortSignal を通知しつつ Promise.race 自体も完了させるため、
 * 呼び出し先が abort を処理できない場合でも画面を永久 loading にしない。
 */
export async function runWithRequestTimeout<T>(
  request: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
): Promise<RequestTimeoutResult<T>> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined

  const requestResult = Promise.resolve()
    .then(() => request(controller.signal))
    .then<RequestTimeoutResult<T>, RequestTimeoutResult<T>>(
      (value) => ({ status: 'success', value }),
      (error) => ({ status: 'error', error }),
    )

  const timeoutResult = new Promise<RequestTimeoutResult<T>>((resolve) => {
    timer = setTimeout(() => {
      controller.abort()
      resolve({ status: 'timeout' })
    }, timeoutMs)
  })

  try {
    return await Promise.race([requestResult, timeoutResult])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export async function requestWithTimeout<T>(
  request: (signal: AbortSignal) => Promise<T>,
  timeoutMs = STARTUP_REQUEST_TIMEOUT_MS,
): Promise<T> {
  const result = await runWithRequestTimeout(request, timeoutMs)
  if (result.status === 'success') return result.value
  if (result.status === 'error') throw result.error
  throw new RequestTimeoutError()
}
