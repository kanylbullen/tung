# The performance and reliability lens

## Mandate

Does it hold up? A phone in a basement gym with no signal, a log that grows
by thousands of sets a year, a list of 680 exercises, a server that syncs
10 000 rows in seconds. You own what the diff makes more expensive or more
fragile in real use.

## Check especially

- The main thread: database work, JSON parsing, catalogue loading or
  progress maths on Main instead of IO or `Dispatchers.Default`; a blocking
  call in a composable or a ViewModel's init.
- Lists: a `LazyColumn` without stable keys, a long list as a `Column`,
  recomposition of a whole screen for one row's change, objects recreated in
  every composition and passed as parameters.
- Queries: one query per row instead of per screen, a Flow over a whole
  table to count ten rows, a missing index on a new filter column, N+1 on
  the server, Exposed in a hot path where sync uses plain JDBC batches.
- Sync and network: retries that can double-apply, a 4xx retried, a 5xx not
  retried, a request without a timeout, a periodic job that runs while
  signed out, push or sync loops that wake each other.
- Reliability across process death: state that should be in
  `SavedStateHandle` or DataStore and is only in memory; a rest timer or
  notification that cannot recover after the process is killed.
- Rate and size limits: notification posts above the system's rate limit,
  unbounded reads of a response or upload, a cache without a bound.

## Out of scope

Correct but clumsy is the correctness lens's finding; module boundaries are
the architecture lens's. You take what affects speed and dependability.
