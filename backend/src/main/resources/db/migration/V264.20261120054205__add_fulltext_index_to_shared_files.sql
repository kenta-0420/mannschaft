-- F04.6 グローバル検索 FILE 種別（CMP-261116-0722 PR-6）: 共有ファイルのキーワード検索用 FULLTEXT 索引
--
-- shared_files(name, description) に ngram パーサの FULLTEXT 索引を新設する。
-- クエリ側は SharedFileKeywordSearchRepository の
-- MATCH(sf.name, sf.description) AGAINST(:keyword IN BOOLEAN MODE)（列の並びは索引と同一である必要がある）。
-- 前例: timeline_posts（V4.001）/ chat_messages（V4.014）/ bulletin_threads（V5.002）/ blog_posts（V6.002）が
-- 同様に WITH PARSER ngram を使用している。テストスキーマ（test-schema-fulltext-indexes.sql）と同名・同定義。
ALTER TABLE shared_files
    ADD FULLTEXT INDEX ft_shared_files_name_description (name, description) WITH PARSER ngram;
