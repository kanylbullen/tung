-- Push endpoints (issue #34): where the server can wake a device with an
-- empty "sync now" when another client changed the user's data. Never
-- content: the device syncs to learn what changed.
--
-- One row per (user, device): a device that registers again (a new FCM
-- token, another UnifiedPush distributor) replaces its transport and
-- endpoint in place and keeps its id, so the unique index is a plain one,
-- not partial: re-registering a revoked device brings its row back
-- (revoked_at cleared). Revoked rows are kept, like api_tokens; the server
-- revokes an endpoint itself when FCM or the distributor answers that it
-- is gone (404/410). Timestamps are epoch millis, like everywhere else.
CREATE TABLE push_endpoints (
    id           text   NOT NULL,
    user_id      text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id    text   NOT NULL,
    transport    text   NOT NULL CHECK (transport IN ('fcm', 'unifiedpush')),
    endpoint     text   NOT NULL,  -- an FCM registration token, or the URL the server POSTs to
    created_at   bigint NOT NULL,
    last_used_at bigint,           -- the last push that was accepted
    revoked_at   bigint,
    PRIMARY KEY (user_id, id)
);
CREATE UNIQUE INDEX push_endpoints_user_device_key ON push_endpoints (user_id, device_id);
