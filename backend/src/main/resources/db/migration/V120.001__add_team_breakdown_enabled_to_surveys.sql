-- (B) 組織→参加チーム配信 案C フェーズB（アンケートのチーム別内訳 by_team）
-- 組織が配下チームへ配信したアンケートの集計を「チームごとの内訳（optionResultsByTeam）」でも
-- 収集・表示するかの作成時トグル列を追加する。既定 false（従来挙動＝by_team は省略・全体集計のみ）。
-- TRUE のときのみ組織アンケート結果が optionResultsByTeam を算出する。
--
-- 匿名保護（御裁可B）:
--   * 匿名アンケート（is_anonymous = TRUE）× team_breakdown_enabled = TRUE の併用は禁止。
--     作成時バリデーションで弾く（SurveyService.createSurvey）。集計側でも二重防御で by_team を出さない。
--   * 非匿名でも回答者 5 名未満のチームは内訳をマスクする（SurveyResultService の
--     MIN_RESPONDENTS_FOR_DETAIL_EXPORT = 5 を流用）。
--
-- NOT NULL DEFAULT FALSE のため既存行も安全（NULL 挿入バグ回避のため Entity 側 @Builder.Default false 必須）。
ALTER TABLE surveys
    ADD COLUMN team_breakdown_enabled BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'アンケート集計をチーム別内訳（by_team）でも収集・表示するか' AFTER include_supporters;
