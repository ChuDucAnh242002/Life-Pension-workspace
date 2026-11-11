CREATE TABLE policy_request (
    id VARCHAR2(36) PRIMARY KEY,
    request_order NUMBER(19) GENERATED ALWAYS AS IDENTITY UNIQUE,
    case_id NUMBER(19) NOT NULL REFERENCES adviser_case(id),
    case_revision NUMBER(19) NOT NULL,
    fixture VARCHAR2(30) NOT NULL,
    status VARCHAR2(20) NOT NULL CHECK (status IN ('QUEUED','WAITING','COMPLETED','TIMED_OUT','FAILED','STALE')),
    requested_by VARCHAR2(100 CHAR) NOT NULL,
    created_at VARCHAR2(35) NOT NULL,
    expires_at NUMBER(19) NOT NULL,
    completed_at VARCHAR2(35),
    attempts NUMBER(10) DEFAULT 0 NOT NULL,
    last_error VARCHAR2(300 CHAR),
    snapshot CLOB
);
CREATE INDEX policy_request_case ON policy_request(case_id, request_order);
CREATE INDEX policy_request_pending ON policy_request(status, expires_at);
