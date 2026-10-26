-- organizations テーブル: public_id を slug に置き換え
-- slug は人間可読なカスタム識別子（3〜30文字の英数字ハイフン）
ALTER TABLE organizations ADD COLUMN slug VARCHAR(30) NULL;

-- name から slug を自動生成（ASCII英数字のみ抽出してハイフン結合）
UPDATE organizations
SET slug = LOWER(TRIM(BOTH '-' FROM REGEXP_REPLACE(
    REGEXP_REPLACE(LOWER(name), '[^a-z0-9]+', '-'),
    '-{2,}', '-'
)))
WHERE deleted_at IS NULL OR deleted_at IS NOT NULL;

-- 3文字未満になったケースのフォールバック
UPDATE organizations SET slug = CONCAT('org-', LPAD(id, 6, '0'))
WHERE CHAR_LENGTH(slug) < 3 OR slug IS NULL OR slug = '' OR slug = '-';

-- slug が30文字超の場合は切り詰め
UPDATE organizations SET slug = LEFT(slug, 30) WHERE CHAR_LENGTH(slug) > 30;

-- 重複する slug にサフィックスを付与（ROW_NUMBER で一意化）
UPDATE organizations t1
INNER JOIN (
    SELECT id,
           slug,
           ROW_NUMBER() OVER (PARTITION BY slug ORDER BY id) AS rn
    FROM organizations
) ranked ON t1.id = ranked.id
SET t1.slug = CASE
    WHEN ranked.rn = 1 THEN t1.slug
    ELSE CONCAT(LEFT(t1.slug, 27), '-', ranked.rn)
END
WHERE ranked.rn > 1;

-- NOT NULL 制約とUNIQUEインデックスを追加
ALTER TABLE organizations MODIFY COLUMN slug VARCHAR(30) NOT NULL;
ALTER TABLE organizations ADD UNIQUE INDEX uq_organizations_slug (slug);

-- public_id カラムを削除
ALTER TABLE organizations DROP INDEX idx_organizations_public_id;
ALTER TABLE organizations DROP COLUMN public_id;
