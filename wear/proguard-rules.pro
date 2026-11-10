# R8 rules for the watch app, the phone's way (app/proguard-rules.pro): libraries (Room,
# kotlinx.serialization, Ktor, WorkManager, Wear Compose, Play services) ship their own
# consumer rules; add a rule here only for something the app itself reaches by reflection,
# with a comment saying what.
