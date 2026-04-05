package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CreateTagRequest;
import com.mannschaft.app.filesharing.dto.TagResponse;
import com.mannschaft.app.filesharing.entity.SharedFileTagEntity;
import com.mannschaft.app.filesharing.repository.SharedFileTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ファイルタグサービス。ファイルに対するタグ管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileTagService {

    private final SharedFileTagRepository tagRepository;
    private final FileSharingMapper fileSharingMapper;

    /**
     * ファイルのタグ一覧を取得する。
     *
     * @param fileId ファイルID
     * @return タグレスポンスリスト
     */
    public List<TagResponse> listTags(Long fileId) {
        List<SharedFileTagEntity> tags = tagRepository.findByFileIdOrderByTagNameAsc(fileId);
        return fileSharingMapper.toTagResponseList(tags);
    }

    /**
     * ファイルにタグを付ける。
     *
     * @param fileId  ファイルID
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたタグレスポンス
     */
    @Transactional
    public TagResponse addTag(Long fileId, Long userId, CreateTagRequest request) {
        if (tagRepository.existsByFileIdAndTagNameAndUserId(fileId, request.getTagName(), userId)) {
            throw new BusinessException(FileSharingErrorCode.TAG_ALREADY_EXISTS);
        }

        SharedFileTagEntity entity = SharedFileTagEntity.builder()
                .fileId(fileId)
                .tagName(request.getTagName())
                .userId(userId)
                .build();

        SharedFileTagEntity saved = tagRepository.save(entity);
        log.info("タグ追加: fileId={}, tagName={}", fileId, request.getTagName());
        return fileSharingMapper.toTagResponse(saved);
    }

    /**
     * ファイルのタグを削除する。
     *
     * @param tagId タグID
     */
    @Transactional
    public void removeTag(Long tagId) {
        SharedFileTagEntity entity = tagRepository.findById(tagId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.TAG_NOT_FOUND));
        tagRepository.delete(entity);
        log.info("タグ削除: tagId={}", tagId);
    }

    /**
     * ユーザーのタグ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return タグレスポンスリスト
     */
    public List<TagResponse> listUserTags(Long userId) {
        List<SharedFileTagEntity> tags = tagRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return fileSharingMapper.toTagResponseList(tags);
    }
}
