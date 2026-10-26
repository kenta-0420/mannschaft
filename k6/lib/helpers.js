/**
 * k6 テスト共通ユーティリティ
 */

import { sleep } from 'k6';

/**
 * min〜max 秒のランダムスリープ（リアルな操作間隔を再現）
 *
 * @param {number} min - 最小秒数
 * @param {number} max - 最大秒数
 */
export function randomSleep(min = 0.5, max = 2.0) {
  sleep(min + Math.random() * (max - min));
}

/**
 * ISO 8601 形式の日時文字列を返す（Spring Boot の DateTimeFormat.ISO.DATE_TIME に対応）
 * 例: "2026-05-20T00:00:00" → "2026-05-20T00:00:00"
 *
 * @param {Date} date
 * @returns {string}
 */
export function toIsoLocal(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * 現在日時から指定日数前後の範囲文字列を返す。
 * OrgScheduleController / TeamScheduleController の from/to パラメータ用。
 *
 * @param {number} daysBack  - 何日前から（デフォルト 30）
 * @param {number} daysForth - 何日後まで（デフォルト 30）
 * @returns {{ from: string, to: string }}
 */
export function dateRange(daysBack = 30, daysForth = 30) {
  const now = new Date();
  const from = new Date(now.getTime() - daysBack * 86400 * 1000);
  const to = new Date(now.getTime() + daysForth * 86400 * 1000);
  return {
    from: toIsoLocal(from),
    to: toIsoLocal(to),
  };
}

/**
 * 配列からランダムな要素を返す。
 *
 * @param {Array} arr
 * @returns {*}
 */
export function randomOf(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
