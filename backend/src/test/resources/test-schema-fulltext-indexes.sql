-- テストスキーマ（ddl-auto: create）に本番 migration 由来の FULLTEXT INDEX を補う（CMP-261116-0722 PR-2）。
-- Hibernate はエンティティから FULLTEXT INDEX を生成できないため、create の直後にこのファイルを実行させる
-- （application-test.yml の hibernate.hbm2ddl.import_files）。コンテキスト作成のたびにスキーマが作り直されるので、
-- テストクラスごとの補完だけに頼るとテストの実行順で「索引の無いスキーマ」に当たり、MATCH ... AGAINST が
-- 「Can't find FULLTEXT index matching the column list」で落ちる（グローバル検索 POST 種別が timedOutTypes に入る）。
-- 1 行 1 文で書くこと（既定の SQL 抽出は 1 行を 1 文として扱う）。定義は本番 V4.001 と一致させる。
ALTER TABLE timeline_posts ADD FULLTEXT INDEX ft_timeline_posts_content (content) WITH PARSER ngram;
ALTER TABLE chat_messages ADD FULLTEXT INDEX ft_msg_body (body) WITH PARSER ngram;
ALTER TABLE bulletin_threads ADD FULLTEXT INDEX ft_bulletin_threads_title_body (title, body) WITH PARSER ngram;
ALTER TABLE blog_posts ADD FULLTEXT INDEX ft_bp_title_body (title, body) WITH PARSER ngram;
ALTER TABLE shared_files ADD FULLTEXT INDEX ft_shared_files_name_description (name, description) WITH PARSER ngram;
