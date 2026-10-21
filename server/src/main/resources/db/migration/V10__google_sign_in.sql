-- Sign in with Google (issue #51). google_sub is the stable subject of a
-- verified Google ID token, set when a user first signs in with Google: on a
-- new account, or on the existing account whose email Google says is
-- verified (the link). A user who only ever signed in with Google has no
-- password, so password_hash becomes nullable; every password check treats
-- null as "password login refused".
ALTER TABLE users
    ADD COLUMN google_sub text UNIQUE,
    ALTER COLUMN password_hash DROP NOT NULL;
