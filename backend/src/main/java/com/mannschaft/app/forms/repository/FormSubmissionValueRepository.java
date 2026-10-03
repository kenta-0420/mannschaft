package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * フォーム提出値リポジトリ。
 */
public interface FormSubmissionValueRepository extends JpaRepository<FormSubmissionValueEntity, Long> {

    /**
     * 提出に属する値一覧を取得する。
     */
    List<FormSubmissionValueEntity> findBySubmissionId(Long submissionId);

    /** 親行をロックした編集処理用。呼出元 transaction の古い RR snapshot を使わない。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM FormSubmissionValueEntity v WHERE v.submissionId = :submissionId")
    List<FormSubmissionValueEntity> findBySubmissionIdForUpdate(Long submissionId);

    /**
     * 提出に属する値を一括削除する。
     */
    void deleteBySubmissionId(Long submissionId);
}
