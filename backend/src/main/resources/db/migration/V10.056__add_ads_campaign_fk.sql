ALTER TABLE ads ADD CONSTRAINT fk_ads_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns(id);
