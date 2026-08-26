-- リフレッシュトークン並行更新の競合制御（grace window 正規化）用の後継ポインタ列を追加する。
--
-- 背景: 複数デバイス／並行リクエストが同一リフレッシュトークンで同時に refresh を叩くと、
--       片方が旧トークンを revoke した直後にもう片方が同じ（使用済みの）トークンを再提示し、
--       これを従来ロジックはリプレイ攻撃と誤判定して logoutAllDevices（全セッション永久無効化）を
--       発火させ、ユーザーが 401 から回復不能に陥る「自爆バグ」があった。
--
-- 対策: ローテーションで後継トークンを発行した際に、その後継トークンのハッシュを本列に記録する。
--       - replaced_by_token_hash が非 NULL       = ローテーションで正規に revoke された印。
--         revoked_at からの経過が grace window 以内なら「並行更新」として正規化し（リプレイ扱いにしない）、
--         grace window を超過していれば真のリプレイとして検知する。
--       - replaced_by_token_hash が NULL かつ revoked = 明示ログアウト等（後継なし revoke）。
--         こちらは grace 対象外として通常のリボーク済みエラーを返す。
--
-- from-scratch / 既存データ両対応: ADD COLUMN ... NULL のみ（既存行は NULL のままで意味が通る）。
--
-- 採番メモ: 現行 origin/main の最大 major は V133。マージ直前に必ず `git ls-tree origin/main` 等で
--          最大 major を再確認し、衝突していれば V<最大major+1>.001 にリネームすること。

ALTER TABLE refresh_tokens
    ADD COLUMN replaced_by_token_hash VARCHAR(64) NULL AFTER token_hash;
