-- F14.1 代理入力・非デジタル住民対応: 本人入力のみを返す集計ビュー（BI接続での集計汚染防止）
CREATE OR REPLACE VIEW v_survey_responses_by_person AS
SELECT * FROM survey_responses WHERE is_proxy_input = 0;
