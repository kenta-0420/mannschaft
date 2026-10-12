/**
 * Vitest グローバルセットアップ。
 *
 * テスト環境（happy-dom）には IndexedDB がないため、fake-indexeddb を注入する。
 * Dexie.js がこの polyfill を検知して使用する。
 */
import 'fake-indexeddb/auto'
import { afterEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

// DOM 環境が終了する前に、各テストでマウントした実コンポーネントを破棄する。
enableAutoUnmount(afterEach)
