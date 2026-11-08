package com.mannschaft.app.auth.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 出生プロフィール命令のPIIを含まない成功応答。再送時の版・確認参照を保持する。 */
@Entity @Table(name="birth_profile_commands") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED) @SuperBuilder(toBuilder=true)
public class BirthProfileCommandEntity extends UuidV7Entity {
    @Column(nullable=false,updatable=false) private Long userId;
    @Column(nullable=false,updatable=false) private UUID commandId;
    @Column(nullable=false,updatable=false,length=64) private String requestHash;
    /** 旧NULL履歴は種別不明として拒否し、応答JSONから推定しない。 */
    @Column(updatable=false,length=30) private String commandType;
    /** RAW_PROFILE_PUTの現在HMAC鍵識別だけ。原入力や旧検証鍵は保存しない。 */
    @Column(updatable=false,length=64) private String requestKeyId;
    @Column(nullable=false,updatable=false,columnDefinition="LONGTEXT") private String responseSnapshot;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false,updatable=false) private Instant updatedAt;
}
