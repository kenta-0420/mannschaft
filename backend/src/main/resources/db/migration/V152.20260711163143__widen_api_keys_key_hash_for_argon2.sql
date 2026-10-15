-- api_keys.key_hash を argon2 対応のため VARCHAR(60) → VARCHAR(255) に拡張する。
--
-- 背景: 共有 passwordEncoder（AuthConfig）は DelegatingPasswordEncoder（既定 argon2）へ移行済みで、
-- encode() は "{argon2}$argon2id$v=19$m=...$...$..." 形式（約100文字）を返す。ApiKeyService.issueApiKey
-- はこの共有エンコーダで key_hash を生成するが、テーブル作成時（V11.151）は旧 bcrypt 前提の
-- VARCHAR(60) であったため、MySQL strict モードで "Data too long for column 'key_hash'" となり
-- APIキー発行が必ず 500 で失敗していた（webhook 認可契約テストが顕在化）。
-- bcrypt(60) / argon2(~100) / 将来アルゴリズムを見越し、パスワードハッシュ列の慣例に倣い 255 とする。
ALTER TABLE api_keys
    MODIFY key_hash VARCHAR(255) NOT NULL COMMENT 'パスワードハッシュ（DelegatingPasswordEncoder: 既定argon2 / 旧bcrypt互換）';
