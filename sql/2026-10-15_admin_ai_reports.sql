CREATE TABLE IF NOT EXISTS ai_response_reports (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    student_note TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolution_note TEXT,
    resolved_by CHAR(36),
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_report_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
    CONSTRAINT fk_ai_report_student FOREIGN KEY (student_id) REFERENCES users(id),
    CONSTRAINT fk_ai_report_resolver FOREIGN KEY (resolved_by) REFERENCES users(id),
    INDEX idx_ai_report_status_created (status, created_at),
    INDEX idx_ai_report_student (student_id)
);

CREATE TABLE IF NOT EXISTS ai_reported_messages (
    id CHAR(36) PRIMARY KEY,
    report_id CHAR(36) NOT NULL,
    message_id CHAR(36) NOT NULL,
    student_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reported_message_report FOREIGN KEY (report_id) REFERENCES ai_response_reports(id),
    CONSTRAINT fk_reported_message_message FOREIGN KEY (message_id) REFERENCES ai_messages(id),
    UNIQUE KEY uq_report_message (report_id, message_id)
);
