package com.mannschaft.app.cms.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** 旧メディアのscope補完が保存済み親だけを信頼し、draftや削除済み親を推測しないことを検証する。 */
@Testcontainers(disabledWithoutDocker = true)
class BlogMediaScopeMigrationIT {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void 保存親のscopeのみを補完し未確定な既存データはNULLを保つ() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE blog_posts (id BIGINT PRIMARY KEY, team_id BIGINT, organization_id BIGINT, user_id BIGINT, deleted_at DATETIME)");
            statement.execute("CREATE TEMPORARY TABLE blog_media_uploads (id BIGINT PRIMARY KEY, blog_post_id BIGINT, s3_key VARCHAR(255))");
            statement.execute("INSERT INTO blog_posts VALUES (1,12,NULL,99,NULL),(2,NULL,22,99,NULL),(3,NULL,NULL,33,NULL),(4,44,NULL,NULL,NOW())");
            statement.execute("INSERT INTO blog_media_uploads VALUES (1,1,'blog/TEAM/999/forged'),(2,2,'blog/TEAM/999/forged2'),(3,3,'blog/TEAM/999/forged3'),(4,NULL,'blog/TEAM/12/draft'),(5,4,'blog/TEAM/44/deleted'),(6,999,'blog/TEAM/12/missing')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V210.20260913093000__persist_blog_media_acl_scope.sql"));
            try (var rows = statement.executeQuery("SELECT id, scope_type, scope_id FROM blog_media_uploads ORDER BY id")) {
                String[] types = {"TEAM", "ORGANIZATION", "PERSONAL", null, null, null};
                Long[] ids = {12L, 22L, 33L, null, null, null};
                for (int i = 0; i < types.length; i++) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("scope_type")).isEqualTo(types[i]);
                    assertThat(rows.getObject("scope_id", Long.class)).isEqualTo(ids[i]);
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
