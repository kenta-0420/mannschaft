/**
 * Vitest グローバルセットアップ。
 *
 * テスト環境（happy-dom）には IndexedDB がないため、fake-indexeddb を注入する。
 * Dexie.js がこの polyfill を検知して使用する。
 */
import 'fake-indexeddb/auto'
