ALTER TABLE equipment_items
  ADD COLUMN amazon_url        VARCHAR(1000) NULL AFTER qr_code,
  ADD COLUMN amazon_asin       VARCHAR(10)   NULL AFTER amazon_url,
  ADD COLUMN rakuten_item_code VARCHAR(200)  NULL AFTER amazon_asin,
  ADD COLUMN reorder_threshold INT UNSIGNED  NULL AFTER rakuten_item_code;
