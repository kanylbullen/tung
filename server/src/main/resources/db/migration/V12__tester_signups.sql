-- People who asked, on the landing page, to test the Android app on Google
-- Play. Play's internal testing takes a list of Google account emails, which
-- the owner copies into the Play Console from GET /api/v1/testers and then
-- deletes here. Nothing else is stored: the address, when, and the page's
-- language, to write the invitation in. Not linked to an account.
CREATE TABLE tester_signups (
    email      text   PRIMARY KEY,
    created_at bigint NOT NULL,
    language   text
);
