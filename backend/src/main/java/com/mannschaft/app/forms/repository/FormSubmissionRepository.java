package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.SubmissionStatus;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * フォーム提出リポジトリ。
 */
public interface FormSubmissionRepository extends JpaRepository<FormSubmissionEntity, Long> {

    /**
     * テンプレートに紐付く提出一覧をページング取得する。
     */
    Page<FormSubmissionEntity> findByTemplateIdOrderByCreatedAtDesc(Long templateId, Pageable pageable);

    /**
     * テンプレートに紐付く提出をステータス指定でページング取得する。
     */
    Page<FormSubmissionEntity> findByTemplateIdAndStatusOrderByCreatedAtDesc(
            Long templateId, SubmissionStatus status, Pageable pageable);

    /**
     * ユーザーのスコープ内提出一覧をページング取得する。
     */
    Page<FormSubmissionEntity> findBySubmittedByAndScopeTypeAndScopeIdOrderByCreatedAtDesc(
            Long submittedBy, String scopeType, Long scopeId, Pageable pageable);

    /**
     * IDと提出者IDで提出を取得する。
     */
    Optional<FormSubmissionEntity> findByIdAndSubmittedBy(Long id, Long submittedBy);

    /**
     * テンプレートとユーザーの提出回数を取得する。
     */
    long countByTemplateIdAndSubmittedBy(Long templateId, Long submittedBy);

    /**
     * テンプレートのステータス別提出件数を取得する。
     */
    long countByTemplateIdAndStatus(Long templateId, SubmissionStatus status);
}
