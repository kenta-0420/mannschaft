package com.mannschaft.app.cms.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** URLに必要な技術metadataだけを源PRIMARYから読み、CVC前にTXを終了する。 */
@Service
@RequiredArgsConstructor
class BlogRanchLinkMetadataReader {
    private final JdbcTemplate jdbc;
    record Metadata(Long teamId, Long organizationId, Long userId, Long socialProfileId, String slug) { }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Optional<Metadata> read(Long id) {
        var rows=jdbc.query("SELECT team_id,organization_id,user_id,social_profile_id,slug FROM blog_posts "
                +"WHERE id=? AND deleted_at IS NULL", (rs,index) -> new Metadata(rs.getObject(1,Long.class),
                rs.getObject(2,Long.class),rs.getObject(3,Long.class),rs.getObject(4,Long.class),rs.getString(5)),id);
        return rows.stream().findFirst();
    }
}
