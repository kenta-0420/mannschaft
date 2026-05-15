/**
 * F18 個人ポイントカードウォレット — オフライン対応用 IndexedDB ストア。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §6.6 / §7.4 / §9.1
 *
 * <h2>仕様概要</h2>
 *
 * <ul>
 *   <li>IndexedDB データベース名: {@code mannschaft-wallet-offline}（既存
 *       {@code mannschaft-offline}（Dexie）とは独立し、ウォレット機能を無効化したり
 *       単独で削除したりできるよう専用 DB として分離する）</li>
 *   <li>オブジェクトストア:
 *     <ul>
 *       <li>{@code groupCache} — グループ詳細の暗号文を保存
 *           （キー: {@code `${userId}:${groupId}`}）</li>
 *       <li>{@code keys} — ユーザーごとの AES-GCM 鍵を保存
 *           （キー: {@code userId}、CryptoKey は extractable:false）</li>
 *     </ul>
 *   </li>
 *   <li>暗号化: Web Crypto API の AES-GCM (256-bit) で二重暗号化
 *       （バックエンド側で平文 SQL に直接保存しないという一段目に加え、
 *        ローカル IndexedDB でも端末固有鍵で暗号化）</li>
 *   <li>鍵生成: {@code crypto.subtle.generateKey} で extractable=false。
 *       端末紛失時にブラウザストレージ全体が取り出されても、CryptoKey は
 *       BLOB として読み出せず実用上の改ざん耐性として機能する
 *       （構造化クローンによる同一オリジン内の流用は可能だが、これは設計書 §9.1 で
 *        受容するリスクとして明記済み）。</li>
 *   <li>TTL: 7 日。読み出し時に {@code expiresAt < Date.now()} なら null を返してエントリを削除する。</li>
 *   <li>ログアウト時: {@code clearAll(userId)} で当該ユーザーの全レコードと鍵を削除する。</li>
 * </ul>
 *
 * <h2>API 設計上のメモ</h2>
 *
 * <p>本ストアは {@code composables/useWalletOffline.ts} 経由でのみ使う想定で、
 * 直接 import するのは composable とログアウト処理だけにとどめること。</p>
 *
 * <p>SSR 環境（{@code import.meta.server}）では {@code indexedDB} と
 * {@code crypto.subtle} が利用できないため、すべての公開 API は SSR 時に no-op を返す。
 * これにより SSR 時のクラッシュを防ぎつつ、クライアント遷移後に正しく動作する。</p>
 */

import type { PointCardGroupDetail } from '~/types/pointCard'

/** IndexedDB データベース名（設計書 §7.4）。 */
const DB_NAME = 'mannschaft-wallet-offline'

/** スキーマバージョン。スキーマ変更時に bump する。 */
const DB_VERSION = 1

/** グループ詳細の暗号文を保存するオブジェクトストア。 */
const STORE_GROUP_CACHE = 'groupCache'

/** ユーザー鍵を保存するオブジェクトストア。 */
const STORE_KEYS = 'keys'

/** TTL = 7 日（ミリ秒）。設計書 §7.4。 */
const TTL_MS = 7 * 24 * 60 * 60 * 1000

/** AES-GCM の IV 長（推奨 96 bit = 12 byte）。 */
const IV_LENGTH_BYTES = 12

// =====================================================================
// 型定義
// =====================================================================

/**
 * IndexedDB に保存する暗号化済みグループキャッシュエントリ。
 * 平文の {@code PointCardGroupDetail} を JSON 化して AES-GCM で暗号化したものを ciphertext に格納する。
 */
interface EncryptedGroupCacheEntry {
  /** {@code `${userId}:${groupId}`} 形式の複合キー。 */
  key: string
  userId: number
  groupId: string
  iv: Uint8Array
  ciphertext: ArrayBuffer
  /** 失効時刻（{@link Date#getTime} 形式の epoch ms）。 */
  expiresAt: number
}

/** {@link OfflineStore} の公開インターフェース。 */
export interface OfflineStore {
  /**
   * グループ詳細をローカル IndexedDB に暗号化保存する。
   * 既存エントリは上書きされる。{@code expiresAt} は現時刻 + 7 日。
   */
  saveGroup(userId: number, groupDetail: PointCardGroupDetail): Promise<void>

  /**
   * グループ詳細を IndexedDB から復号して取り出す。
   * - 未保存 → {@code null}
   * - TTL 切れ → {@code null}（同時にエントリ削除）
   * - 鍵未生成 / 復号失敗 → {@code null}
   */
  loadGroup(userId: number, groupId: string): Promise<PointCardGroupDetail | null>

  /**
   * 当該ユーザーの全キャッシュ + 鍵を削除する。
   * ログアウト時 / 設定からの「キャッシュ削除」操作時に呼ぶ。
   */
  clearAll(userId: number): Promise<void>

  /**
   * 当該ユーザーの鍵を破棄して再生成する。
   * 既存キャッシュは復号不可能になるため全削除する。再ログイン直後に呼ぶことを想定。
   */
  refreshKey(userId: number): Promise<void>
}

// =====================================================================
// IndexedDB ヘルパ（Promise 化）
// =====================================================================

/** SSR 環境かどうか判定する。 */
function isClient(): boolean {
  return typeof indexedDB !== 'undefined' && typeof crypto !== 'undefined' && !!crypto.subtle
}

/**
 * IndexedDB をオープンする（必要に応じてスキーマ作成）。
 * 各 API 呼び出しごとに open するため接続のリーク無しでシンプルに扱える。
 */
function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_GROUP_CACHE)) {
        // keyPath は composite key "userId:groupId"
        db.createObjectStore(STORE_GROUP_CACHE, { keyPath: 'key' })
      }
      if (!db.objectStoreNames.contains(STORE_KEYS)) {
        // keyPath は userId
        db.createObjectStore(STORE_KEYS, { keyPath: 'userId' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB open failed'))
  })
}

/** {@link IDBRequest} を Promise に変換する。 */
function reqToPromise<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB request failed'))
  })
}

/** {@link IDBTransaction} の完了を Promise で待つ。 */
function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error ?? new Error('IndexedDB transaction failed'))
    tx.onabort = () => reject(tx.error ?? new Error('IndexedDB transaction aborted'))
  })
}

// =====================================================================
// 鍵管理
// =====================================================================

/** keys ストアに保存される鍵レコード。 */
interface StoredKeyRecord {
  userId: number
  key: CryptoKey
}

/**
 * 当該ユーザーの暗号鍵を取得する。未生成の場合は新規に生成して保存する。
 *
 * 鍵は {@code extractable: false} で生成するため、JS から raw バイト列を取り出すことはできない。
 * IndexedDB に保存する CryptoKey は structured clone のため、同一オリジンの JS からのみ参照可能。
 */
async function getOrCreateKey(userId: number): Promise<CryptoKey> {
  const db = await openDb()
  try {
    const tx = db.transaction(STORE_KEYS, 'readonly')
    const existing = await reqToPromise(
      tx.objectStore(STORE_KEYS).get(userId) as IDBRequest<StoredKeyRecord | undefined>,
    )
    if (existing) {
      return existing.key
    }
  } finally {
    db.close()
  }

  // 新規生成: AES-GCM 256-bit, extractable=false
  const newKey = await crypto.subtle.generateKey(
    { name: 'AES-GCM', length: 256 },
    /* extractable */ false,
    ['encrypt', 'decrypt'],
  )

  const db2 = await openDb()
  try {
    const tx = db2.transaction(STORE_KEYS, 'readwrite')
    tx.objectStore(STORE_KEYS).put({ userId, key: newKey } satisfies StoredKeyRecord)
    await txDone(tx)
  } finally {
    db2.close()
  }
  return newKey
}

// =====================================================================
// 暗号化・復号
// =====================================================================

/**
 * UTF-8 文字列を {@link ArrayBuffer} で返す。
 * TypeScript 5.7+ の {@code Uint8Array<ArrayBufferLike>} と Web Crypto の {@code BufferSource} の
 * 不整合を避けるため、明示的に {@link ArrayBuffer} に整形してから返す。
 */
function utf8EncodeToBuffer(s: string): ArrayBuffer {
  const u8 = new TextEncoder().encode(s)
  // 新規の ArrayBuffer を確保して slice する（SharedArrayBuffer 混入を避ける）
  const buf = new ArrayBuffer(u8.byteLength)
  new Uint8Array(buf).set(u8)
  return buf
}

/** ArrayBuffer → UTF-8 文字列。 */
function utf8Decode(buf: ArrayBuffer): string {
  return new TextDecoder().decode(buf)
}

/**
 * 平文オブジェクトを AES-GCM で暗号化する。
 * IV は呼び出しごとにランダム生成する（同じ鍵で同じ IV を再利用すると AES-GCM の機密性が崩れるため）。
 */
async function encrypt(
  key: CryptoKey,
  plaintext: string,
): Promise<{ iv: Uint8Array; ciphertext: ArrayBuffer }> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH_BYTES))
  // BufferSource として ArrayBuffer を直接渡す（Uint8Array<ArrayBufferLike> 互換性問題を回避）
  const ivBuffer = new ArrayBuffer(iv.byteLength)
  new Uint8Array(ivBuffer).set(iv)
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: ivBuffer },
    key,
    utf8EncodeToBuffer(plaintext),
  )
  return { iv, ciphertext }
}

/** AES-GCM で復号する。改ざんされていれば DOMException が投げられる。 */
async function decrypt(
  key: CryptoKey,
  iv: Uint8Array,
  ciphertext: ArrayBuffer,
): Promise<string> {
  // IV を ArrayBuffer に正規化（読み出した Uint8Array は ArrayBufferLike の可能性があるため）
  const ivBuffer = new ArrayBuffer(iv.byteLength)
  new Uint8Array(ivBuffer).set(iv)
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ivBuffer }, key, ciphertext)
  return utf8Decode(plain)
}

// =====================================================================
// composite key 生成
// =====================================================================

function makeCacheKey(userId: number, groupId: string): string {
  return `${userId}:${groupId}`
}

// =====================================================================
// OfflineStore 実装
// =====================================================================

/**
 * オフラインストアを生成する。シングルトン的な使い方を想定しているが、
 * 内部状態を持たないので毎回 new しても問題ない（IndexedDB は open ごとにハンドルが分かれる）。
 */
export function createOfflineStore(): OfflineStore {
  return {
    async saveGroup(userId, groupDetail) {
      if (!isClient()) return
      const key = await getOrCreateKey(userId)
      const plaintext = JSON.stringify(groupDetail)
      const { iv, ciphertext } = await encrypt(key, plaintext)
      const entry: EncryptedGroupCacheEntry = {
        key: makeCacheKey(userId, groupDetail.id),
        userId,
        groupId: groupDetail.id,
        iv,
        ciphertext,
        expiresAt: Date.now() + TTL_MS,
      }

      const db = await openDb()
      try {
        const tx = db.transaction(STORE_GROUP_CACHE, 'readwrite')
        tx.objectStore(STORE_GROUP_CACHE).put(entry)
        await txDone(tx)
      } finally {
        db.close()
      }
    },

    async loadGroup(userId, groupId) {
      if (!isClient()) return null
      const cacheKey = makeCacheKey(userId, groupId)

      const db = await openDb()
      let entry: EncryptedGroupCacheEntry | undefined
      try {
        const tx = db.transaction(STORE_GROUP_CACHE, 'readonly')
        entry = await reqToPromise(
          tx.objectStore(STORE_GROUP_CACHE).get(cacheKey) as IDBRequest<
            EncryptedGroupCacheEntry | undefined
          >,
        )
      } finally {
        db.close()
      }

      if (!entry) return null

      // TTL 切れチェック
      if (entry.expiresAt < Date.now()) {
        // 期限切れエントリを削除（fire-and-forget は使わず確実に消す）
        const db2 = await openDb()
        try {
          const tx = db2.transaction(STORE_GROUP_CACHE, 'readwrite')
          tx.objectStore(STORE_GROUP_CACHE).delete(cacheKey)
          await txDone(tx)
        } finally {
          db2.close()
        }
        return null
      }

      // 鍵を取り出して復号
      try {
        const key = await getOrCreateKey(userId)
        const plaintext = await decrypt(key, entry.iv, entry.ciphertext)
        return JSON.parse(plaintext) as PointCardGroupDetail
      } catch {
        // 復号失敗（鍵不一致や改ざん）。エントリを削除して null を返す。
        // 設計書 §9.1 の脅威モデルでは「改ざん検出 = 復号失敗」を期待動作として扱う。
        const db2 = await openDb()
        try {
          const tx = db2.transaction(STORE_GROUP_CACHE, 'readwrite')
          tx.objectStore(STORE_GROUP_CACHE).delete(cacheKey)
          await txDone(tx)
        } finally {
          db2.close()
        }
        return null
      }
    },

    async clearAll(userId) {
      if (!isClient()) return
      const db = await openDb()
      try {
        const tx = db.transaction([STORE_GROUP_CACHE, STORE_KEYS], 'readwrite')
        // 鍵を削除（=以降の復号不可）
        tx.objectStore(STORE_KEYS).delete(userId)
        // 当該ユーザーのキャッシュエントリだけを削除する
        // （他のユーザーが同端末を使うケースに備えて全消しではなく userId フィルタにする）
        const cacheStore = tx.objectStore(STORE_GROUP_CACHE)
        const cursorReq = cacheStore.openCursor()
        await new Promise<void>((resolve, reject) => {
          cursorReq.onsuccess = () => {
            const cursor = cursorReq.result
            if (cursor) {
              const value = cursor.value as EncryptedGroupCacheEntry
              if (value.userId === userId) {
                cursor.delete()
              }
              cursor.continue()
            } else {
              resolve()
            }
          }
          cursorReq.onerror = () => reject(cursorReq.error ?? new Error('cursor failed'))
        })
        await txDone(tx)
      } finally {
        db.close()
      }
    },

    async refreshKey(userId) {
      if (!isClient()) return
      // 既存鍵を破棄しキャッシュも全削除（鍵差し替え後は復号不可能のため）
      await this.clearAll(userId)
      // 次回 saveGroup / loadGroup 呼び出し時に getOrCreateKey が新規鍵を生成する
    },
  }
}

/**
 * IndexedDB データベース自体を削除する（ログアウト時の最終クリーンアップ用）。
 * Dexie のように個別 store のクリアではなく DB ごと飛ばすことで、
 * スキーマ変更 / 旧版バグの残骸が残らないようにする。
 */
export function deleteWalletOfflineDb(): Promise<void> {
  if (!isClient()) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const req = indexedDB.deleteDatabase(DB_NAME)
    req.onsuccess = () => resolve()
    req.onerror = () => reject(req.error ?? new Error('deleteDatabase failed'))
    req.onblocked = () => {
      // 他タブが DB を開いている。ログアウト時は閉じきれないこともあるので
      // ここでは resolve して握りつぶす（次回起動時に削除される）。
      resolve()
    }
  })
}
