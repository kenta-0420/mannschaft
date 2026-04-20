-- 組織ジャンル刷新（Issue 13）
-- 既存データのマイグレーション（NONPROFIT→NPO, FORPROFIT→COMPANY）
UPDATE organizations SET org_type = 'NPO' WHERE org_type = 'NONPROFIT';
UPDATE organizations SET org_type = 'COMPANY' WHERE org_type = 'FORPROFIT';

-- enumカラムの定義更新（9種）
ALTER TABLE organizations MODIFY COLUMN org_type
  ENUM('GOVERNMENT','MUNICIPALITY','COMPANY','HOSPITAL','ASSOCIATION','SCHOOL','NPO','COMMUNITY','OTHER')
  NOT NULL DEFAULT 'OTHER';
