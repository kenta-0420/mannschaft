package com.mannschaft.app.village.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.village.dto.MyJoinRequestResponse.VillageState;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CMP-260826-1456: 申請先の村の名前と状態の写像（村の行が無い場合を含む）を固定する。 */
@DisplayName("MyJoinRequestResponse 申請先の村の写像")
class MyJoinRequestResponseTest {

    private static VillageJoinRequestEntity request() {
        return VillageJoinRequestEntity.builder()
                .villageId(UUID.randomUUID()).requesterUserId(1L)
                .subjectType(VillageSubjectType.USER).subjectId(1L)
                .status(VillageRequestStatus.PENDING).build();
    }

    @Test
    @DisplayName("通常の村は名前とACTIVEを返す")
    void active() {
        VillageEntity village = VillageEntity.builder().name("桜村").build();
        MyJoinRequestResponse response = MyJoinRequestResponse.of(request(), village);
        assertThat(response.villageName()).isEqualTo("桜村");
        assertThat(response.villageState()).isEqualTo(VillageState.ACTIVE);
    }

    @Test
    @DisplayName("凍結済みはARCHIVED、論理削除はDELETED（削除が優先）で名前を保つ")
    void archivedAndDeleted() {
        VillageEntity archived = VillageEntity.builder().name("梅村").build();
        archived.setArchivedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(MyJoinRequestResponse.of(request(), archived).villageState()).isEqualTo(VillageState.ARCHIVED);
        assertThat(MyJoinRequestResponse.of(request(), archived).villageName()).isEqualTo("梅村");

        archived.setDeletedAt(LocalDateTime.of(2026, 9, 2, 0, 0));
        assertThat(MyJoinRequestResponse.of(request(), archived).villageState()).isEqualTo(VillageState.DELETED);
        assertThat(MyJoinRequestResponse.of(request(), archived).villageName()).isEqualTo("梅村");
    }

    @Test
    @DisplayName("村の行が存在しない場合は名前null・DELETED")
    void missingVillage() {
        MyJoinRequestResponse response = MyJoinRequestResponse.of(request(), null);
        assertThat(response.villageName()).isNull();
        assertThat(response.villageState()).isEqualTo(VillageState.DELETED);
    }
}
