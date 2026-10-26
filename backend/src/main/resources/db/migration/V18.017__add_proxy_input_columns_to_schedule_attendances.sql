-- F14.1 代理入力・非デジタル住民対応: schedule_attendances テーブルに集計分離カラムを追加
ALTER TABLE schedule_attendances
    ADD COLUMN is_proxy_input        TINYINT(1)      NOT NULL DEFAULT 0
        COMMENT '代理入力フラグ（0=本人入力, 1=代理入力）（F14.1）',
    ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL
        COMMENT '代理入力ログID FK→proxy_input_records.id（F14.1）',
    ADD CONSTRAINT fk_sa_proxy_record
        FOREIGN KEY (proxy_input_record_id) REFERENCES proxy_input_records(id);
