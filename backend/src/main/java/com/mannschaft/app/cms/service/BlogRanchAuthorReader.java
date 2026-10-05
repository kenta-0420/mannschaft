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
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean isAuthor(Long id,Long actor) {
        return jdbc.queryForList("SELECT author_id FROM blog_posts WHERE id=? AND deleted_at IS NULL",Long.class,id)
                .stream().anyMatch(actor::equals);
    }
}
