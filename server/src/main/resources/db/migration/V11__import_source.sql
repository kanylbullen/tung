-- Import from other apps (issue #5): the app a workout was imported from
-- (core's ImportBundle.source, e.g. 'jefit') and the import run that wrote it
-- (a UUID), so "new record" leaves imported workouts out and one import can be
-- undone. Set once by the importing phone and synced; null on every workout
-- logged in Tung and on every row from before. A write that carries null
-- keeps the stored values (a client that does not know them).
ALTER TABLE workouts
    ADD COLUMN import_source text,
    ADD COLUMN import_batch_id text;
