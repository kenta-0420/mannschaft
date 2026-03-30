-- インシデントコメント添付ファイルテーブル
CREATE TABLE incident_comment_attachments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id  BIGINT       NOT NULL,
    file_key    VARCHAR(500) NOT NULL,
    mime_type   VARCHAR(50)  NOT NULL,
    created_by  BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ica_comment     FOREIGN KEY (comment_id) REFERENCES incident_comments (id),
    CONSTRAINT fk_ica_created_by  FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_ica_comment (comment_id)
);
