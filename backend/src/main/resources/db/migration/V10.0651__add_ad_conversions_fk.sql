ALTER TABLE ad_conversions ADD CONSTRAINT fk_ad_conversions_click FOREIGN KEY (click_id) REFERENCES ad_clicks(id);
ALTER TABLE ad_conversions ADD CONSTRAINT fk_ad_conversions_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns(id);
ALTER TABLE ad_conversions ADD CONSTRAINT fk_ad_conversions_ad FOREIGN KEY (ad_id) REFERENCES ads(id);
