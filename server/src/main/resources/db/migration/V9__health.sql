-- Health Connect on the phone (issue #45): what it read for a finished workout
-- (average and maximum heart rate in bpm, active kilocalories) and the id of
-- the exercise session it wrote there, so no device writes the session twice.
-- The server never reads Health Connect; it stores what the phone syncs. All
-- nullable, null on every row from before; a write that carries null keeps the
-- stored value (a client without Health Connect, or that does not know them).
ALTER TABLE workouts
    ADD COLUMN avg_heart_rate integer,
    ADD COLUMN max_heart_rate integer,
    ADD COLUMN active_calories integer,
    ADD COLUMN health_connect_id text;
