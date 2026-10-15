ALTER TABLE users
  ADD COLUMN country_code CHAR(2) NULL DEFAULT NULL
    COMMENT 'ISO 3166-1 alpha-2 国コード（例: JP・US・DE）。カレンダー祝日表示用。NULL時はlocaleから推定'
  AFTER locale;
