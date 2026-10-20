-- Who started a workout (issue #34): one of core's StartedBy values
-- (phone, watch, server, agent), synced as started_by; null when unknown,
-- as on every row from before this column. Set when the workout is created
-- and never changed afterwards: a write that carries null keeps the stored
-- value (an older client that does not know the field).
ALTER TABLE workouts ADD COLUMN started_by text;
