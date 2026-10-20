-- tung-server schema v1.
--
-- Ids are text UUIDs. Users and API tokens get theirs from the server; all
-- training data keeps the id the client generated (core's Ids.kt), so a row has
-- the same id on the phone and here. Every timestamp is epoch millis (bigint),
-- like the app.
--
-- Training data is keyed by (user_id, id), never by id alone: the same id under
-- two users is two rows, so one user can never write into another's data by
-- sending their id. Every user table has updated_at, compared row by row
-- (newer wins, equal or older is ignored), and deleted_at, the tombstone that
-- sync will carry. Routines and programs are not in v1.

CREATE TABLE users (
    id            text PRIMARY KEY,
    email         text   NOT NULL UNIQUE,  -- stored trimmed and lower-cased
    password_hash text   NOT NULL,         -- argon2id, PHC string format
    created_at    bigint NOT NULL
);

-- Both session tokens (name 'session', from login/register) and tokens made
-- by hand for scripts and agents. Only the SHA-256 of the token is stored.
CREATE TABLE api_tokens (
    id           text PRIMARY KEY,
    user_id      text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         text   NOT NULL,
    token_hash   text   NOT NULL UNIQUE,
    scope        text   NOT NULL CHECK (scope IN ('read', 'write')),
    created_at   bigint NOT NULL,
    last_used_at bigint,
    revoked_at   bigint
);
CREATE INDEX api_tokens_user_idx ON api_tokens (user_id);

-- Catalogue exercises have user_id NULL and are seeded from catalog/core.json
-- on every start. A user's row with the same id as a catalogue row is that
-- user's copy of it (made when they change its rest time) and shadows the
-- catalogue row for them only.
CREATE TABLE exercises (
    user_id      text REFERENCES users (id) ON DELETE CASCADE,
    id           text    NOT NULL,
    name_key     text,
    name         text    NOT NULL,
    muscle_group text    NOT NULL,
    equipment    text    NOT NULL,
    is_custom    boolean NOT NULL,
    rest_seconds integer,
    updated_at   bigint  NOT NULL,
    deleted_at   bigint,
    CONSTRAINT exercises_user_id_id_key UNIQUE NULLS NOT DISTINCT (user_id, id)
);

CREATE TABLE workouts (
    user_id        text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id             text   NOT NULL,
    started_at     bigint NOT NULL,
    ended_at       bigint,
    note           text,
    routine_id     text,  -- not a foreign key, as in the app
    program_day_id text,  -- not a foreign key, as in the app
    updated_at     bigint NOT NULL,
    deleted_at     bigint,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX workouts_user_started_idx ON workouts (user_id, started_at DESC);

CREATE TABLE workout_exercises (
    user_id     text    NOT NULL,
    id          text    NOT NULL,
    workout_id  text    NOT NULL,
    exercise_id text    NOT NULL,  -- a catalogue or an own exercise; checked by the server
    position    integer NOT NULL,
    updated_at  bigint  NOT NULL,
    deleted_at  bigint,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_id) REFERENCES workouts (user_id, id) ON DELETE CASCADE
);
CREATE INDEX workout_exercises_workout_idx ON workout_exercises (user_id, workout_id);
CREATE INDEX workout_exercises_exercise_idx ON workout_exercises (user_id, exercise_id);

CREATE TABLE sets (
    user_id             text             NOT NULL,
    id                  text             NOT NULL,
    workout_exercise_id text             NOT NULL,
    position            integer          NOT NULL,
    weight_kg           double precision NOT NULL,
    reps                integer          NOT NULL,
    completed           boolean          NOT NULL,
    updated_at          bigint           NOT NULL,
    deleted_at          bigint,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_exercise_id) REFERENCES workout_exercises (user_id, id) ON DELETE CASCADE
);
CREATE INDEX sets_workout_exercise_idx ON sets (user_id, workout_exercise_id);
