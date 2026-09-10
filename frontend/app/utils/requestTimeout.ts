export type RequestTimeoutResult<T> =
  | { status: 'success'; value: T }
  | { status: 'error'; error: unknown }
  | { status: 'timeout' }

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
