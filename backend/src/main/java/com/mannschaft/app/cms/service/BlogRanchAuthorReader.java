package com.mannschaft.app.cms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 非本人・削除済みの既存公開経路を保ち、本人候補だけを狭いnative writerへ渡す。 */
@Service
@RequiredArgsConstructor
class BlogRanchAuthorReader {
    private final JdbcTemplate jdbc;
    /** 短いPRIMARY TXを終了してからauthor資格のauth lockへ進む。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    java.util.Optional<Long> author(Long id) {
        return jdbc.queryForList("SELECT author_id FROM blog_posts WHERE id=? AND deleted_at IS NULL",Long.class,id)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean isAuthor(Long id,Long actor) {
        return jdbc.queryForList("SELECT author_id FROM blog_posts WHERE id=? AND deleted_at IS NULL",Long.class,id)
                .stream().anyMatch(actor::equals);
    }
    /** 最大50の源IDを先読みし、auth窓口を開く前に短いPRIMARY TXを終える。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    java.util.List<Long> authors(java.util.List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>50 || ids.stream().anyMatch(id -> id==null || id<=0))
            throw new IllegalArgumentException("ブログ資格先読み入力が不正です");
        return jdbc.queryForList("SELECT DISTINCT author_id FROM blog_posts WHERE id IN ("
                +String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+") AND deleted_at IS NULL LIMIT 51",
                Long.class,ids.toArray());
    }}
