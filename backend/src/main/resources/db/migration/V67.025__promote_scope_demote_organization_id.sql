-- F09.17 Phase 11-d-1: scope_type / scope_id NOT NULL 昇格 + organization_id NULL 許可降格
--
-- V67.024 で backfill 完了済みのため、scope_type / scope_id を NOT NULL に昇格する。
-- 同時に organization_id は NULL 許可に降格する（チーム広告主では scope_type='TEAM' / scope_id=team_id となり
-- organization_id は NULL になる可能性があるため）。
--
-- なお advertiser_accounts.organization_id には V10.060 で FK 制約
-- fk_advertiser_accounts_organization (REFERENCES organizations(id)) が張られているが、
-- NULL 許可化しても FK 制約自体は維持される（NULL 値は FK 検査の対象外）。
-- ad_messaging_campaigns.organization_id は FK 制約なし（クロスドメイン参照 INDEX のみ）。

ALTER TABLE advertiser_accounts
    MODIFY scope_type VARCHAR(20) NOT NULL,
    MODIFY scope_id BIGINT UNSIGNED NOT NULL,
    MODIFY organization_id BIGINT UNSIGNED NULL;

ALTER TABLE ad_messaging_campaigns
    MODIFY scope_type VARCHAR(20) NOT NULL,
    MODIFY scope_id BIGINT UNSIGNED NOT NULL,
    MODIFY organization_id BIGINT UNSIGNED NULL;
