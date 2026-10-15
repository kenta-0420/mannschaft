-- F03.4.5 §6.2（W2-5）: 定期予約（毎週繰り返し）— series 表現カラムを追加する。
--
-- 会員が「この予約を毎週繰り返す」を選ぶと、同一ライン・同一曜日・同一時間帯の枠を最大 12 週分
-- まとめて予約する。各週は独立した予約行であり、それらを束ねる論理キーが recurring_series_id。
--
-- 設計判断（§6.2「series 表現」）:
--   * 専用親テーブルは作らない。group_id（F03.4.3・同日連続枠の横軸）と同じ「兄弟行方式」を採り、
--     アプリ層で UUIDv7 を採番する。group_id とは独立直交し併存可能
--     （同日連続枠のグループを毎週繰り返す、という組み合わせも表現できる）。
--   * NULL = 従来どおりの単発予約。repeatWeeks 省略 / 1 のときは NULL のまま
--     （既存契約と完全に同一・§6.2 / AC-5-2）。
--   * 成立が 1 件だけになった場合（2 週目以降が全てスキップ）も NULL に戻す。
--     「1 行だけの series」は単発予約と区別する意味がないため（AC-5-13）。
--
-- 既存行の充足について:
--   NULL 許容・DEFAULT なしのため既存行は NULL のまま（= 単発予約）で、backfill は不要。
--   既存の予約一覧・詳細・キャンセル動線の挙動は一切変わらない。
--
-- クロスドメインFK（原則1）:
--   recurring_series_id はアプリ層採番の論理キーであり参照先テーブルを持たない（FK なし）。
--
-- INDEX:
--   idx_rv_series — series 内の兄弟行を引く唯一の経路
--   （「以降すべてキャンセル」= THIS_AND_FOLLOWING、`scope=SERIES` の一括承認）。
--   ユーザー軸 / チーム軸の絞り込みは既存の idx（user_id / team_id）と併用される。

ALTER TABLE reservations
    ADD COLUMN recurring_series_id BINARY(16) NULL AFTER menu_id,
    ADD INDEX idx_rv_series (recurring_series_id);
