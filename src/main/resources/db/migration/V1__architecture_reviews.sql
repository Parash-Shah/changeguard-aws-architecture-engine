CREATE TABLE review (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_key VARCHAR(200) UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    format VARCHAR(32) NOT NULL,
    report TEXT NOT NULL,
    snapshot TEXT NOT NULL,
    suppressions TEXT NOT NULL
);
CREATE TABLE rule_definition (
    id VARCHAR(100) NOT NULL,
    version VARCHAR(64) NOT NULL,
    definition TEXT NOT NULL,
    PRIMARY KEY (id, version)
);
CREATE TABLE resource (
    review_id UUID NOT NULL REFERENCES review(id),
    resource_id TEXT NOT NULL,
    resource_type VARCHAR(200) NOT NULL,
    properties TEXT NOT NULL,
    PRIMARY KEY (review_id, resource_id)
);
CREATE TABLE finding (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES review(id),
    rule_id VARCHAR(100) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    resource_id TEXT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    classification VARCHAR(16) NOT NULL,
    suppressed BOOLEAN NOT NULL,
    detail TEXT NOT NULL,
    FOREIGN KEY (rule_id, rule_version) REFERENCES rule_definition(id, version)
);
CREATE INDEX finding_review_idx ON finding(review_id);
CREATE TABLE review_milestone (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES review(id),
    name VARCHAR(200) NOT NULL,
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (review_id, name)
);
CREATE TABLE architecture_baseline (
    name VARCHAR(200) PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES review(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
