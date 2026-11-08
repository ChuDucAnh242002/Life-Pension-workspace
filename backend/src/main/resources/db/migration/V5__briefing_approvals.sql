CREATE TABLE briefing_approval (
    draft_id VARCHAR2(36) PRIMARY KEY REFERENCES briefing_draft(id),
    approved_at VARCHAR2(35) NOT NULL,
    approved_by VARCHAR2(100 CHAR) NOT NULL,
    review_note VARCHAR2(1000 CHAR),
    reviewed_sources NUMBER(1) NOT NULL CHECK (reviewed_sources = 1)
);
