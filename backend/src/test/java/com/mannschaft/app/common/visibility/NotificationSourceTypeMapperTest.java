package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase F: {@link NotificationSourceTypeMapper} の単体テスト。
 *
 * <p>NotificationEntity.sourceType 文字列が ReferenceType に正しく
 * 解決されること、未対応・null は Optional.empty() を返すことを検証する。
 */
@DisplayName("F00 Phase F: NotificationSourceTypeMapper")
class NotificationSourceTypeMapperTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SCHEDULE,SCHEDULE",
            "TIMELINE_POST,TIMELINE_POST",
            "CHAT_MESSAGE,CHAT_MESSAGE",
            "BULLETIN_THREAD,BULLETIN_THREAD",
            "BLOG_POST,BLOG_POST",
            "EVENT,EVENT",
            "ACTIVITY_RESULT,ACTIVITY_RESULT",
            "RECRUITMENT_LISTING,RECRUITMENT_LISTING",
            "RECRUITMENT,RECRUITMENT_LISTING",
            "SURVEY,SURVEY",
            "TOURNAMENT,TOURNAMENT",
            "JOB_POSTING,JOB_POSTING",
            "CIRCULATION_DOCUMENT,CIRCULATION_DOCUMENT",
            "CIRCULATION,CIRCULATION_DOCUMENT",
            "PHOTO_ALBUM,PHOTO_ALBUM",
            "COMMENT,COMMENT",
            "FILE_ATTACHMENT,FILE_ATTACHMENT",
            "TEAM,TEAM",
            "ORGANIZATION,ORGANIZATION",
            "PERSONAL_TIMETABLE,PERSONAL_TIMETABLE"
    })
    @DisplayName("既知 sourceType は対応する ReferenceType に解決される")
    void resolves_known_source_types(String sourceType, ReferenceType expected) {
        Optional<ReferenceType> result = NotificationSourceTypeMapper.resolve(sourceType);
        assertThat(result).contains(expected);
        assertThat(NotificationSourceTypeMapper.isMapped(sourceType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MEMBER_PAYMENT",   // 未マップ (Resolver 未配備)
            "JOB_CONTRACT",     // 未マップ (Resolver 未配備)
            "JOB_APPLICATION",  // 未マップ
            "TODO",             // 未マップ (TODO は ReferenceType に未登録)
            "SYSTEM",           // システム通知
            "CONFIRMABLE_NOTIFICATION",  // 確認通知自体は対象外
            "UNKNOWN_FUTURE_TYPE"        // 未知の文字列
    })
    @DisplayName("未対応 sourceType は Optional.empty() を返す (fail-soft)")
    void returns_empty_for_unmapped_source_types(String sourceType) {
        Optional<ReferenceType> result = NotificationSourceTypeMapper.resolve(sourceType);
        assertThat(result).isEmpty();
        assertThat(NotificationSourceTypeMapper.isMapped(sourceType)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("null は Optional.empty() を返す")
    void returns_empty_for_null(String sourceType) {
        Optional<ReferenceType> result = NotificationSourceTypeMapper.resolve(sourceType);
        assertThat(result).isEmpty();
        assertThat(NotificationSourceTypeMapper.isMapped(sourceType)).isFalse();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("空文字列は Optional.empty() を返す")
    void returns_empty_for_empty_string() {
        Optional<ReferenceType> result = NotificationSourceTypeMapper.resolve("");
        assertThat(result).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("大文字小文字は厳密一致 (小文字は未マップ扱い)")
    void is_case_sensitive() {
        // sourceType は規約上 ASCII 大文字なので、小文字は未マップ
        assertThat(NotificationSourceTypeMapper.resolve("blog_post")).isEmpty();
        assertThat(NotificationSourceTypeMapper.resolve("BlogPost")).isEmpty();
    }
}
