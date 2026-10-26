-- Plain SQL that SQLite and PostgreSQL both accept. Times are epoch milliseconds.

-- One row per Microsoft message ever copied, keyed by its immutable id. The row is what keeps a
-- copied message from being copied again, even after the copy is deleted at the destination.
CREATE TABLE receipt (
    account             VARCHAR(200)  NOT NULL,
    message_id          VARCHAR(1000) NOT NULL,
    -- PENDING: upload started, outcome not yet known. IMPORTED: the destination holds the copy.
    -- REMOVED: the source was moved to Deleted Items afterwards.
    state               VARCHAR(20)   NOT NULL,
    source_folder       VARCHAR(1000) NOT NULL,
    destination         VARCHAR(200)  NOT NULL,
    destination_folder  VARCHAR(1000) NOT NULL,
    internet_message_id VARCHAR(2000),
    content_hash        VARCHAR(128)  NOT NULL,
    size_bytes          BIGINT        NOT NULL,
    destination_uid     BIGINT,
    created_at          BIGINT        NOT NULL,
    imported_at         BIGINT,
    removed_at          BIGINT,
    last_error          TEXT,
    PRIMARY KEY (account, message_id)
);

-- Where the next delta round of one account's source folder starts.
CREATE TABLE checkpoint (
    account         VARCHAR(200)  NOT NULL,
    source_folder   VARCHAR(1000) NOT NULL,
    delta_link      TEXT,
    -- The removal setting the last round ran with. Switching removal on restarts the folder from
    -- the beginning, so messages copied earlier get removed too.
    removal_enabled BOOLEAN       NOT NULL,
    updated_at      BIGINT        NOT NULL,
    PRIMARY KEY (account, source_folder)
);

CREATE TABLE account_status (
    account         VARCHAR(200) NOT NULL PRIMARY KEY,
    last_attempt_at BIGINT       NOT NULL,
    last_success_at BIGINT,
    imported        INTEGER      NOT NULL,
    removed         INTEGER      NOT NULL,
    skipped         INTEGER      NOT NULL,
    failed          INTEGER      NOT NULL,
    last_error      TEXT
);

-- A single row that a poll holds while it runs, so two processes sharing a database never poll
-- at the same time. An expired lease belongs to a process that died.
CREATE TABLE poll_lease (
    id         INTEGER NOT NULL PRIMARY KEY,
    holder     VARCHAR(200),
    expires_at BIGINT
);

INSERT INTO poll_lease (id, holder, expires_at) VALUES (1, NULL, NULL);
