package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import java.time.Instant;
import java.util.UUID;

/** 実ユーザー情報を用いず、無料・未所属の本人を作る。 */
final class RanchTestFixture {
    private RanchTestFixture() { }
    static UserEntity user() {
        return UserEntity.builder().email("ranch-" + UUID.randomUUID() + "@example.invalid")
                .lastName("試験").firstName("本人").displayName("牧場試験")
                .status(UserStatus.ACTIVE).isSearchable(false).locale("ja").timezone("Asia/Tokyo").build();
    }

    /** Hibernate createの試験DBに、V245の本番初期値と同じ一行を毎case固定する。 */
    static void operationalControl(RanchOperationalControlRepository repository) {
        Instant seededAt = Instant.parse("2026-10-04T00:00:00Z");
        repository.saveAndFlush(RanchOperationalControlEntity.builder().id(1)
                .careEnabled(false).shopEnabled(false).deliveryPaused(true)
                .version(0).createdAt(seededAt).updatedAt(seededAt).build());
    }
}
