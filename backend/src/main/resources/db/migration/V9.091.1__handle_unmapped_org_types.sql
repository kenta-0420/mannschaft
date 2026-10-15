-- V9.091 で見落とした未定義 org_type 値を救済する補修migration。
--
-- 経緯:
-- V9.091 は NONPROFIT/FORPROFIT のみマップしたが、
-- 一部環境に手動INSERTされた FEDERATION 等の値が残存し ALTER に失敗した
-- （V9.091 のコミット 6cc7409b で FEDERATION の存在が見落とされていた）。
--
-- 対応方針:
-- 1. FEDERATION → ASSOCIATION（連盟・協会の意味合いに最も近い）
-- 2. それ以外の新ENUM 9種に該当しない未知値は OTHER へフォールバック（セーフティネット）
-- 3. ALTER を再実行（V9.091 が中途半端だった環境でも完了させる、冪等）
--
-- 本番デプロイ前の確認手順は docs/operations/PRODUCTION_DEPLOY_CHECKLIST.md を参照。

-- 1. FEDERATION → ASSOCIATION
UPDATE organizations
   SET org_type = 'ASSOCIATION'
 WHERE org_type = 'FEDERATION';

-- 2. 新ENUM 9種に該当しない未知値はすべて OTHER へ救済
UPDATE organizations
   SET org_type = 'OTHER'
 WHERE org_type NOT IN
       ('GOVERNMENT','MUNICIPALITY','COMPANY','HOSPITAL',
        'ASSOCIATION','SCHOOL','NPO','COMMUNITY','OTHER');

-- 3. ALTER 再実行（V9.091 が失敗した環境のリトライ。冪等）
ALTER TABLE organizations MODIFY COLUMN org_type
  ENUM('GOVERNMENT','MUNICIPALITY','COMPANY','HOSPITAL',
       'ASSOCIATION','SCHOOL','NPO','COMMUNITY','OTHER')
  NOT NULL DEFAULT 'OTHER';
