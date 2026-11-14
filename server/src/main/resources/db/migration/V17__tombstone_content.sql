-- A deleted row keeps nothing of what it held (issue #148).
--
-- A delete is a tombstone: the row stays, with deleted_at set, so that sync
-- can tell every other device. Until now it also kept every value it had: a
-- workout's note, heart rate and calories, a set's weight and reps, a
-- routine's name. Sync needs only the ids, updated_at and deleted_at, and
-- the clients' own tombstones carry nothing else.
--
-- One trigger per table clears the content whenever a row is written with
-- deleted_at set, whatever path wrote it (sync, commands, REST, MCP), so a
-- new delete path cannot forget it. Nullable columns become NULL, NOT NULL
-- ones a neutral value. Kept: the keys (id, the parent ids, which are not
-- personal and which the foreign keys need), a program's mode and the structure
-- (position, target sets and reps, weekdays). A live row pushed later over a
-- tombstone brings its own values, as before.
--
-- Exercises are left alone: a deleted own exercise keeps its name because
-- finished workouts still name it (MCP's history reads deleted own rows), and
-- an exercise holds no training data of its own.

CREATE FUNCTION clear_tombstone_workout() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN
        NEW.started_at := 0;
        NEW.ended_at := NULL;
        NEW.note := NULL;
        NEW.routine_id := NULL;
        NEW.program_day_id := NULL;
        NEW.started_by := NULL;
        NEW.avg_heart_rate := NULL;
        NEW.max_heart_rate := NULL;
        NEW.active_calories := NULL;
        NEW.health_connect_id := NULL;
        NEW.import_source := NULL;
        NEW.import_batch_id := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION clear_tombstone_set() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN
        NEW.weight_kg := 0;
        NEW.reps := 0;
        NEW.completed := false;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION clear_tombstone_routine() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN
        NEW.name := '';
        NEW.last_used_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION clear_tombstone_program() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL THEN
        NEW.name := '';
        NEW.is_active := false;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workouts_clear_tombstone  BEFORE INSERT OR UPDATE ON workouts  FOR EACH ROW EXECUTE FUNCTION clear_tombstone_workout();
CREATE TRIGGER sets_clear_tombstone      BEFORE INSERT OR UPDATE ON sets      FOR EACH ROW EXECUTE FUNCTION clear_tombstone_set();
CREATE TRIGGER routines_clear_tombstone  BEFORE INSERT OR UPDATE ON routines  FOR EACH ROW EXECUTE FUNCTION clear_tombstone_routine();
CREATE TRIGGER programs_clear_tombstone  BEFORE INSERT OR UPDATE ON programs  FOR EACH ROW EXECUTE FUNCTION clear_tombstone_program();

-- The tombstones already stored. The seq trigger is off meanwhile: every
-- device already deleted these rows, so they need not be sent again.
ALTER TABLE workouts  DISABLE TRIGGER workouts_seq;
ALTER TABLE sets      DISABLE TRIGGER sets_seq;
ALTER TABLE routines  DISABLE TRIGGER routines_seq;
ALTER TABLE programs  DISABLE TRIGGER programs_seq;

UPDATE workouts  SET deleted_at = deleted_at WHERE deleted_at IS NOT NULL;
UPDATE sets      SET deleted_at = deleted_at WHERE deleted_at IS NOT NULL;
UPDATE routines  SET deleted_at = deleted_at WHERE deleted_at IS NOT NULL;
UPDATE programs  SET deleted_at = deleted_at WHERE deleted_at IS NOT NULL;

ALTER TABLE workouts  ENABLE TRIGGER workouts_seq;
ALTER TABLE sets      ENABLE TRIGGER sets_seq;
ALTER TABLE routines  ENABLE TRIGGER routines_seq;
ALTER TABLE programs  ENABLE TRIGGER programs_seq;
