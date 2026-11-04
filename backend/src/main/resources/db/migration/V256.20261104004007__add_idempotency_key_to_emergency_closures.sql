-- CMP-261104-0334: 臨時休業通知送信の冪等性（連打・応答消失後の再送で多重送信しない）。
--
-- idempotency_key: クライアントが Idempotency-Key ヘッダで送る UUID（小文字正規化済み）。ヘッダ無しの従来送信は NULL。
-- request_hash   : 正規化した要求内容（期間・時間帯・理由・件名・本文・取消フラグ）の SHA-256（16進64文字）。
--                  同じキーで内容が違う再送を 409 で拒否するための照合値。
--
-- 一意制約は (team_id, created_by, idempotency_key)。冪等キーはチーム×送信者で区別する。
-- MySQL の UNIQUE は NULL を重複扱いしないため、キー無しの既存行・従来送信は何件あっても制約に当たらない。
-- 同じキーの同時送信は後着側の INSERT がこの制約で失敗し、業務TX（休業行・予約取消・outbox・確認行）ごと
-- 巻き戻ったうえで、アプリ層が既存行を読み直して同じ結果を返す（制約名はアプリ層が判別に使うため変更禁止）。
-- 既存データは両列 NULL のまま変更しない。FK は張らない（同一表内の列追加のみ）。
ALTER TABLE emergency_closures
    ADD COLUMN idempotency_key CHAR(36) NULL COMMENT '送信の冪等キー（Idempotency-Keyヘッダ・UUID小文字）。ヘッダ無しの送信はNULL',
    ADD COLUMN request_hash CHAR(64) NULL COMMENT '正規化した要求内容のSHA-256（16進）。同一キーで内容が異なる再送の判別に使う',
    ADD CONSTRAINT uq_emergency_closures_team_creator_idempotency UNIQUE (team_id, created_by, idempotency_key);
