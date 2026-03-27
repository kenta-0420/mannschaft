package com.mannschaft.app.family;

import com.mannschaft.app.family.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * family モジュールの Entity ビジネスメソッドのテスト。
 */
@DisplayName("Family Entity 単体テスト")
class FamilyEntityTest {

    // ===================== ShoppingListItemEntity =====================

    @Nested
    @DisplayName("ShoppingListItemEntity")
    class ShoppingListItemEntityTests {

        @Test
        @DisplayName("update: 全フィールドが更新される")
        void update_全フィールド更新() {
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").createdBy(100L).build();

            item.update("豆乳", "2本", "大豆", 200L, 5);

            assertThat(item.getName()).isEqualTo("豆乳");
            assertThat(item.getQuantity()).isEqualTo("2本");
            assertThat(item.getNote()).isEqualTo("大豆");
            assertThat(item.getAssignedTo()).isEqualTo(200L);
            assertThat(item.getSortOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("toggleCheck: 未チェック → チェック済みになる")
        void toggleCheck_チェック済みになる() {
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").isChecked(false).createdBy(100L).build();

            item.toggleCheck(100L);

            assertThat(item.getIsChecked()).isTrue();
            assertThat(item.getCheckedBy()).isEqualTo(100L);
            assertThat(item.getCheckedAt()).isNotNull();
        }

        @Test
        @DisplayName("toggleCheck: チェック済み → 未チェックになる")
        void toggleCheck_未チェックになる() {
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").isChecked(true).createdBy(100L).build();

            item.toggleCheck(100L);

            assertThat(item.getIsChecked()).isFalse();
            assertThat(item.getCheckedBy()).isNull();
            assertThat(item.getCheckedAt()).isNull();
        }

        @Test
        @DisplayName("uncheckItem: チェックが解除される")
        void uncheckItem_解除() {
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").isChecked(true).createdBy(100L).build();

            item.uncheckItem();

            assertThat(item.getIsChecked()).isFalse();
        }

        @Test
        @DisplayName("clearAssignment: 担当者がクリアされる")
        void clearAssignment_クリア() {
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").assignedTo(200L).createdBy(100L).build();

            item.clearAssignment();

            assertThat(item.getAssignedTo()).isNull();
        }
    }

    // ===================== DutyRotationEntity =====================

    @Nested
    @DisplayName("DutyRotationEntity")
    class DutyRotationEntityTests {

        @Test
        @DisplayName("update: 全フィールドが更新される")
        void update_全フィールド更新() {
            DutyRotationEntity entity = DutyRotationEntity.builder()
                    .teamId(1L).dutyName("掃除").rotationType(RotationType.DAILY)
                    .memberOrder("[1,2,3]").startDate(LocalDate.of(2025, 1, 1))
                    .isEnabled(true).createdBy(100L).build();

            entity.update("料理", RotationType.WEEKLY, "[2,3,1]",
                    LocalDate.of(2025, 2, 1), "🍳", false);

            assertThat(entity.getDutyName()).isEqualTo("料理");
            assertThat(entity.getRotationType()).isEqualTo(RotationType.WEEKLY);
            assertThat(entity.getMemberOrder()).isEqualTo("[2,3,1]");
            assertThat(entity.getIcon()).isEqualTo("🍳");
            assertThat(entity.getIsEnabled()).isFalse();
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_deletedAt設定() {
            DutyRotationEntity entity = DutyRotationEntity.builder()
                    .teamId(1L).dutyName("掃除").rotationType(RotationType.DAILY)
                    .memberOrder("[1,2,3]").startDate(LocalDate.of(2025, 1, 1))
                    .isEnabled(true).createdBy(100L).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("updateMemberOrder: memberOrderが更新される")
        void updateMemberOrder_更新() {
            DutyRotationEntity entity = DutyRotationEntity.builder()
                    .teamId(1L).dutyName("掃除").rotationType(RotationType.DAILY)
                    .memberOrder("[1,2,3]").startDate(LocalDate.of(2025, 1, 1))
                    .isEnabled(true).createdBy(100L).build();

            entity.updateMemberOrder("[3,1,2]");

            assertThat(entity.getMemberOrder()).isEqualTo("[3,1,2]");
        }

        @Test
        @DisplayName("disable: isEnabledがfalseになる")
        void disable_無効化() {
            DutyRotationEntity entity = DutyRotationEntity.builder()
                    .teamId(1L).dutyName("掃除").rotationType(RotationType.DAILY)
                    .memberOrder("[1,2,3]").startDate(LocalDate.of(2025, 1, 1))
                    .isEnabled(true).createdBy(100L).build();

            entity.disable();

            assertThat(entity.getIsEnabled()).isFalse();
        }
    }

    // ===================== TeamAnniversaryEntity =====================

    @Nested
    @DisplayName("TeamAnniversaryEntity")
    class TeamAnniversaryEntityTests {

        @Test
        @DisplayName("update: 全フィールドが更新される")
        void update_全フィールド更新() {
            TeamAnniversaryEntity entity = TeamAnniversaryEntity.builder()
                    .teamId(1L).name("創部記念日").date(LocalDate.of(2000, 4, 1))
                    .repeatAnnually(true).notifyDaysBefore(3).createdBy(100L).build();

            entity.update("結婚記念日", LocalDate.of(2010, 6, 15), false, 7);

            assertThat(entity.getName()).isEqualTo("結婚記念日");
            assertThat(entity.getDate()).isEqualTo(LocalDate.of(2010, 6, 15));
            assertThat(entity.getRepeatAnnually()).isFalse();
            assertThat(entity.getNotifyDaysBefore()).isEqualTo(7);
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_deletedAt設定() {
            TeamAnniversaryEntity entity = TeamAnniversaryEntity.builder()
                    .teamId(1L).name("記念日").date(LocalDate.of(2025, 1, 1))
                    .repeatAnnually(true).notifyDaysBefore(1).createdBy(100L).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== ShoppingListEntity =====================

    @Nested
    @DisplayName("ShoppingListEntity")
    class ShoppingListEntityTests {

        @Test
        @DisplayName("rename: 名前が更新される")
        void rename_更新() {
            ShoppingListEntity entity = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").status(ShoppingListStatus.ACTIVE)
                    .isTemplate(false).createdBy(100L).build();

            entity.rename("日用品");

            assertThat(entity.getName()).isEqualTo("日用品");
        }

        @Test
        @DisplayName("archive: ステータスがARCHIVEDになる")
        void archive_アーカイブ() {
            ShoppingListEntity entity = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").status(ShoppingListStatus.ACTIVE)
                    .isTemplate(false).createdBy(100L).build();

            entity.archive();

            assertThat(entity.getStatus()).isEqualTo(ShoppingListStatus.ARCHIVED);
        }

        @Test
        @DisplayName("softDelete: deletedAtが設定される")
        void softDelete_deletedAt設定() {
            ShoppingListEntity entity = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").status(ShoppingListStatus.ACTIVE)
                    .isTemplate(false).createdBy(100L).build();

            entity.softDelete();

            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ===================== PresenceEventEntity =====================

    @Nested
    @DisplayName("PresenceEventEntity")
    class PresenceEventEntityTests {

        @Test
        @DisplayName("markReturned: returnedAtが設定される")
        void markReturned_設定() {
            PresenceEventEntity entity = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.GOING_OUT).build();

            entity.markReturned();

            assertThat(entity.getReturnedAt()).isNotNull();
        }

        @Test
        @DisplayName("updateOverdueLevel: overdueレベルが更新される")
        void updateOverdueLevel_更新() {
            PresenceEventEntity entity = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.GOING_OUT).build();

            entity.updateOverdueLevel(2);

            assertThat(entity.getOverdueLevel()).isEqualTo(2);
        }
    }
}
