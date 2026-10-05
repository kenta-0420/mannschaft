package com.mannschaft.app.common.ranchsource;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.service.BlogRanchRewardLinkProvider;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.ReflectionRanchRewardLinkProvider;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleRanchRewardLinkProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 実Guard・源reader・既CVC/実MySQLの現在ACL。HTTP・pool2測定・TL/TEAM記事は別証明。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SourceRewardLinkProviderIT extends AbstractMySqlIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserOperationGuard guard;
    @Autowired BlogPostRepository posts;
    @Autowired ReflectionThemeRepository themes;
    @Autowired ReflectionEntryRepository entries;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleAttendanceRepository responses;
    @Autowired BlogRanchRewardLinkProvider blog;
    @Autowired ReflectionRanchRewardLinkProvider reflection;
    @Autowired ScheduleRanchRewardLinkProvider attendance;
    @Autowired JdbcTemplate jdbc;
    private Long owner,other,postId,scheduleId,responseId;
    private UUID themeId,entryId;
    @BeforeEach void fixture() {
        owner=user();other=user();
        postId=posts.saveAndFlush(BlogPostEntity.builder().authorId(owner).userId(owner).title("検証")
                .slug("source-link-"+UUID.randomUUID()).body("本文はリンクDTOへ入らない")
                .visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED).build()).getId();
        themeId=themes.saveAndFlush(ReflectionThemeEntity.builder().userId(owner).title("検証").build()).getId();
        entryId=entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(themeId)
                .targetDate(LocalDate.of(2026,10,5)).structuredContent("{\"free_note\":\"非公開本文\"}").build()).getId();
        scheduleId=schedules.saveAndFlush(ScheduleEntity.builder().userId(owner).title("予定")
                .startAt(LocalDateTime.of(2026,10,10,0,0)).eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED).attendanceRequired(true).build()).getId();
        responseId=responses.saveAndFlush(ScheduleAttendanceEntity.builder().scheduleId(scheduleId)
                .userId(owner).status(AttendanceStatus.ABSENT).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if(responseId!=null) responses.deleteById(responseId);
        if(scheduleId!=null) schedules.deleteById(scheduleId);
        if(entryId!=null) jdbc.update("DELETE FROM reflection_entries WHERE id=?",bytes(entryId));
        if(themeId!=null) themes.deleteById(themeId);
        if(postId!=null) posts.deleteById(postId);
        if(owner!=null) users.deleteById(owner);
        if(other!=null) users.deleteById(other);
    }
    @Test void reflectionUsesCurrentOwnerAndDropsLogicallyDeletedEntry() {
        var reference=ref(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,RanchRewardEnvelope.IdType.UUID,entryId.toString());
        var own=guard.withActiveUser(owner,() -> reflection.resolve(owner,reference));
        assertThat(own).contains(new SourceRewardLink(SourceRewardLink.Kind.REFLECTION_ENTRY,entryId.toString(),"/reflections/entries/"+entryId));
        var foreign=guard.withActiveUser(other,() -> reflection.resolve(other,reference));
        assertThat(foreign).isEmpty();
        jdbc.update("UPDATE reflection_entries SET deleted_at='2026-10-05 00:00:00' WHERE id=?",bytes(entryId));
        var deleted=guard.withActiveUser(owner,() -> reflection.resolve(owner,reference));
        assertThat(deleted).isEmpty();
    }
    @Test void blogReturnsSourceSlugRouteAndRechecksUnpublishedStatus() {
        var reference=ref(RanchRewardSourceType.BLOG_FIRST_PUBLISH,RanchRewardEnvelope.IdType.LONG,postId.toString());
        var value=guard.withActiveUser(owner,() -> blog.resolve(owner,reference));
        assertThat(value).isPresent();
        assertThat(value.orElseThrow().url()).isEqualTo("/users/"+owner+"/blog/posts/"+posts.findById(postId).orElseThrow().getSlug());
        jdbc.update("UPDATE blog_posts SET status='DRAFT',author_id=? WHERE id=?",other,postId);
        var hidden=guard.withActiveUser(owner,() -> blog.resolve(owner,reference));
        assertThat(hidden).isEmpty();
    }
    @Test void attendanceResolvesResponseIdToCurrentScheduleAndDeletedParentDisappears() {
        var reference=ref(RanchRewardSourceType.ATTENDANCE_RESPONSE,RanchRewardEnvelope.IdType.LONG,responseId.toString());
        var value=guard.withActiveUser(owner,() -> attendance.resolve(owner,reference));
        assertThat(value).contains(new SourceRewardLink(SourceRewardLink.Kind.SCHEDULE,scheduleId.toString(),"/calendar?scheduleId="+scheduleId));
        jdbc.update("UPDATE schedules SET deleted_at='2026-10-05 00:00:00' WHERE id=?",scheduleId);
        var deleted=guard.withActiveUser(owner,() -> attendance.resolve(owner,reference));
        assertThat(deleted).isEmpty();
        jdbc.update("UPDATE schedules SET deleted_at=NULL WHERE id=?",scheduleId);
    }
    @Test void unknownReflectionReferenceReturnsNoLink() {
        var unknown=ref(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,RanchRewardEnvelope.IdType.UUID,UuidV7.generate().toString());
        var result=guard.withActiveUser(owner,() -> reflection.resolve(owner,unknown));
        assertThat(result).isEmpty();
    }
    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@source-link.invalid")
                .firstName("検証").lastName("本人").displayName("検証").locale("ja").timezone("UTC")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false).build()).getId();
    }
    private static SourceRewardReference ref(RanchRewardSourceType type,RanchRewardEnvelope.IdType idType,String id) {
        return new SourceRewardReference(type,idType,id);
    }
    private static byte[] bytes(UUID id) {return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
}
