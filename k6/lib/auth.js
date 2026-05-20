/**
 * 認証共通ライブラリ
 *
 * 実際のエンドポイント調査結果:
 *   POST /api/v1/auth/login
 *   リクエスト: { email, password, rememberMe, deviceFingerprint }
 *   レスポンス: ApiResponse<TokenResponse>
 *     - data.accessToken  : Bearer トークン文字列
 *     - data.refreshToken : リフレッシュトークン文字列
 *     - data.tokenType    : "Bearer"
 *     - data.expiresIn    : 有効期限（秒）
 *     - data.sessionId    : セッション ID（Long）
 *
 * MFA 未設定ユーザーは上記 TokenResponse が返る。
 * MFA 設定済みユーザーは SessionResponse が返る場合がある（k6 テストでは MFA なしユーザーを使用）。
 */

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, TEST_USER } from '../config/env.local.js';

/**
 * ログインして accessToken を返す。
 * ログイン失敗時は null を返す。
 *
 * @returns {string|null} accessToken
 */
export function login() {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      email: TEST_USER.email,
      password: TEST_USER.password,
      rememberMe: false,
      deviceFingerprint: null,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: { name: 'auth_login' },
    }
  );

  const ok = check(res, {
    'login: status 200': (r) => r.status === 200,
    'login: has accessToken': (r) => {
      try {
        return r.json('data.accessToken') !== undefined && r.json('data.accessToken') !== null;
      } catch (_) {
        return false;
      }
    },
  });

  if (!ok) {
    console.error(`ログイン失敗: status=${res.status}, body=${res.body}`);
    return null;
  }

  return res.json('data.accessToken');
}

/**
 * Bearer トークンを含む Authorization ヘッダーオブジェクトを返す。
 *
 * @param {string} token - accessToken
 * @returns {{ headers: Object }}
 */
export function authHeaders(token) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}

/**
 * JSON のみを受け付ける GET 用ヘッダー（Content-Type なし）を返す。
 *
 * @param {string} token - accessToken
 * @returns {{ headers: Object }}
 */
export function authGetHeaders(token) {
  return {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };
}
