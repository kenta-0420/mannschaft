/**
 * 最新の要求の応答だけを反映するための世代ガード。
 *
 * 要求の直前に `next()` を呼んで世代を進め、返った判定関数を保持する。
 * 応答が届いたとき、判定関数が false（より新しい要求が始まっている）なら、その応答は捨てる。
 * 組織の切替や連続操作で応答の到着順が前後しても、古い応答が最新の画面を上書きしない。
 */
export function createLatestGuard() {
  let generation = 0
  return {
    next(): () => boolean {
      generation += 1
      const mine = generation
      return () => mine === generation
    },
  }
}
