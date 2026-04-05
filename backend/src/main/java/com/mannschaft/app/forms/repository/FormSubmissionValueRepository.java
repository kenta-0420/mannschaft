package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * フォーム提出値リポジトリ。
 */
public interface FormSubmissionValueRepository extends JpaRepository<FormSubmissionValueEntity, Long> {

    /**
     * 提出に属する値一覧を取得する。
     */
    List<FormSubmissionValueEntity> findBySubmissionId(Long submissionId);

    /**
     * 提出に属する値を一括削除する。
     */
    void deleteBySubmissionId(Long submissionId);
}
