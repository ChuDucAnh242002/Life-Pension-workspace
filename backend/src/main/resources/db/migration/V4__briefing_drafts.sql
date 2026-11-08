CREATE TABLE briefing_draft (
    id VARCHAR2(36) PRIMARY KEY,
    draft_order NUMBER(19) GENERATED ALWAYS AS IDENTITY UNIQUE,
    case_id NUMBER(19) NOT NULL REFERENCES adviser_case(id),
    snapshot CLOB NOT NULL
);
CREATE INDEX briefing_draft_case ON briefing_draft(case_id, draft_order);
