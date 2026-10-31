-- Feedback from the apps and the web: a bug or an idea in the user's own
-- words, with up to three screenshots. Only a signed-in user sends it, so
-- the row names the account; the admins (TUNG_ADMIN_EMAILS) list it,
-- mark it resolved and delete it. Goes with the account.
CREATE TABLE feedback (
    id          text   PRIMARY KEY,
    user_id     text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        text   NOT NULL,  -- bug | idea
    message     text   NOT NULL,
    platform    text   NOT NULL,  -- android | wear | web | ios
    app_version text,
    created_at  bigint NOT NULL,
    resolved_at bigint
);
CREATE INDEX feedback_by_created ON feedback (created_at DESC);
CREATE INDEX feedback_by_user ON feedback (user_id, created_at);

CREATE TABLE feedback_images (
    id           text    PRIMARY KEY,
    feedback_id  text    NOT NULL REFERENCES feedback (id) ON DELETE CASCADE,
    position     integer NOT NULL,
    content_type text    NOT NULL,  -- image/png | image/jpeg | image/webp, read from the bytes
    size         integer NOT NULL,  -- bytes of data, for the storage budgets without reading it
    data         bytea   NOT NULL
);
CREATE INDEX feedback_images_by_feedback ON feedback_images (feedback_id, position);
