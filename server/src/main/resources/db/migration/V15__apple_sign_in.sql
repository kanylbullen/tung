-- Sign in with Apple. apple_sub is the stable subject of a verified Apple ID
-- token, set like google_sub (V10): on a new account, or on the existing
-- account whose email Apple vouches for (the link). An account may carry both.
ALTER TABLE users ADD COLUMN apple_sub text UNIQUE;
