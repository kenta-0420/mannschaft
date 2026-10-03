-- F08.1: マッチング募集検索の日本語部分一致対応
--
-- match_requests.ft_mr_search（V8.003 で作成）は WITH PARSER 未指定のため、
-- MySQL 標準の FULLTEXT パーサが適用される。標準パーサは連続する日本語文字
-- （スペース区切りが無い CJK 文字列）を単語境界で分割できず、1つの巨大な
-- トークンとして扱ってしまうため、「サッカー」のような部分一致キーワードで
-- MATCH ... AGAINST が一致しない（日本語キーワード検索が事実上機能しない）。
--
-- ngram パーサ（MySQL 8.0 組込・追加インストール不要）へ切り替えることで、
-- 日本語文字列を N-gram（既定 ngram_token_size=2）単位でトークン化し、
-- 2 文字以上のキーワードで部分一致検索ができるようにする。
-- 前例: timeline_posts（V4.001）/ chat_messages（V4.014）/ bulletin_threads（V5.002）/
-- blog_posts（V6.002）/ kb_pages（V11.130）が同様に WITH PARSER ngram を使用している。
--
-- クエリ側（MatchRequestRepository#searchByKeyword の MATCH(mr.title, mr.activity_detail)
-- AGAINST(:keyword IN BOOLEAN MODE)）は無改修。索引レベルの修正のみで根治する。
ALTER TABLE match_requests DROP INDEX ft_mr_search;
ALTER TABLE match_requests
    ADD FULLTEXT INDEX ft_mr_search (title, activity_detail) WITH PARSER ngram;
