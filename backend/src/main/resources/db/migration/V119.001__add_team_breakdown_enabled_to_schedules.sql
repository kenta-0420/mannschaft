-- (B) 組織→参加チーム配信 案C フェーズB（出欠のチーム別内訳 by_team）
-- 組織が配下チームへ配信した出欠確認の集計を「チームごとの内訳（by_team）」でも収集・表示するかの
-- 作成時トグル列を追加する。既定 false（従来挙動＝by_team は省略・全体集計のみ）。
-- TRUE のときのみ組織出欠集計が by_team を算出する。
-- NOT NULL DEFAULT FALSE のため既存行も安全（NULL 挿入バグ回避のため Entity 側 @Builder.Default false 必須）。
ALTER TABLE schedules
    ADD COLUMN team_breakdown_enabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '出欠確認の集計をチーム別内訳（by_team）でも収集・表示するか' AFTER include_supporters;
