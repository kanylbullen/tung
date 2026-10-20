-- tung-server schema v2: sync (issue #25).
--
-- 1. Routines and programs, mirroring the app's Room entities, keyed by
--    (user_id, id) like the rest of the training data.
-- 2. A global sequence, sync_seq, stamped as `seq` on every insert and every
--    update of the eight synced tables by one trigger, so no write path can
--    forget it. A client's cursor is the highest seq it has seen; the next
--    sync returns the user's rows with seq > cursor.
-- 3. The foreign keys between training-data tables become DEFERRABLE
--    INITIALLY DEFERRED: they are checked at commit, not per statement, so the
--    order of rows inside one sync request does not matter. A child whose
--    parent is still missing at commit fails the request (409).

CREATE TABLE routines (
    user_id      text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id           text   NOT NULL,
    name         text   NOT NULL,
    last_used_at bigint,
    updated_at   bigint NOT NULL,
    deleted_at   bigint,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE routine_exercises (
    user_id     text    NOT NULL,
    id          text    NOT NULL,
    routine_id  text    NOT NULL,
    exercise_id text    NOT NULL,  -- a catalogue or an own exercise; checked by the server
    position    integer NOT NULL,
    target_sets integer NOT NULL,
    target_reps integer,
    updated_at  bigint  NOT NULL,
    deleted_at  bigint,
    PRIMARY KEY (user_id, id),
    CONSTRAINT routine_exercises_routine_fkey FOREIGN KEY (user_id, routine_id)
        REFERENCES routines (user_id, id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX routine_exercises_routine_idx ON routine_exercises (user_id, routine_id);

CREATE TABLE programs (
    user_id    text    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id         text    NOT NULL,
    name       text    NOT NULL,
    mode       text    NOT NULL,  -- core.ProgramMode key: fixed or rotating
    is_active  boolean NOT NULL,
    updated_at bigint  NOT NULL,
    deleted_at bigint,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE program_days (
    user_id    text    NOT NULL,
    id         text    NOT NULL,
    program_id text    NOT NULL,
    routine_id text    NOT NULL,
    position   integer NOT NULL,
    weekdays   integer NOT NULL,  -- ISO weekdays as bits, Monday in bit 0
    updated_at bigint  NOT NULL,
    deleted_at bigint,
    PRIMARY KEY (user_id, id),
    CONSTRAINT program_days_program_fkey FOREIGN KEY (user_id, program_id)
        REFERENCES programs (user_id, id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT program_days_routine_fkey FOREIGN KEY (user_id, routine_id)
        REFERENCES routines (user_id, id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX program_days_program_idx ON program_days (user_id, program_id);
CREATE INDEX program_days_routine_idx ON program_days (user_id, routine_id);

-- The v1 foreign keys, under the names Postgres gave them.
ALTER TABLE workout_exercises ALTER CONSTRAINT workout_exercises_user_id_workout_id_fkey DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE sets ALTER CONSTRAINT sets_user_id_workout_exercise_id_fkey DEFERRABLE INITIALLY DEFERRED;

CREATE SEQUENCE sync_seq;

ALTER TABLE exercises         ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE workouts          ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE workout_exercises ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE sets              ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE routines          ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE routine_exercises ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE programs          ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');
ALTER TABLE program_days      ADD COLUMN seq bigint NOT NULL DEFAULT nextval('sync_seq');

-- Every insert and every update takes a fresh seq, whatever the statement set.
-- An upsert whose WHERE (updated_at < incoming) rejects the update changes no
-- row and so no seq.
CREATE FUNCTION bump_sync_seq() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.seq := nextval('sync_seq');
    RETURN NEW;
END;
$$;

CREATE TRIGGER exercises_seq         BEFORE INSERT OR UPDATE ON exercises         FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER workouts_seq          BEFORE INSERT OR UPDATE ON workouts          FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER workout_exercises_seq BEFORE INSERT OR UPDATE ON workout_exercises FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER sets_seq              BEFORE INSERT OR UPDATE ON sets              FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER routines_seq          BEFORE INSERT OR UPDATE ON routines          FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER routine_exercises_seq BEFORE INSERT OR UPDATE ON routine_exercises FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER programs_seq          BEFORE INSERT OR UPDATE ON programs          FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();
CREATE TRIGGER program_days_seq      BEFORE INSERT OR UPDATE ON program_days      FOR EACH ROW EXECUTE FUNCTION bump_sync_seq();

CREATE INDEX exercises_user_seq_idx         ON exercises         (user_id, seq);
CREATE INDEX workouts_user_seq_idx          ON workouts          (user_id, seq);
CREATE INDEX workout_exercises_user_seq_idx ON workout_exercises (user_id, seq);
CREATE INDEX sets_user_seq_idx              ON sets              (user_id, seq);
CREATE INDEX routines_user_seq_idx          ON routines          (user_id, seq);
CREATE INDEX routine_exercises_user_seq_idx ON routine_exercises (user_id, seq);
CREATE INDEX programs_user_seq_idx          ON programs          (user_id, seq);
CREATE INDEX program_days_user_seq_idx      ON program_days      (user_id, seq);
