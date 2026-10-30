// Picked up by the Kotlin Gradle plugin into the generated karma.conf.js.
// A test that writes a full sync page (20 000 rows) to the real IndexedDB
// takes more than Mocha's default 2 s in headless Chrome.
config.set({
  client: {
    mocha: {
      timeout: 30000,
    },
  },
});
