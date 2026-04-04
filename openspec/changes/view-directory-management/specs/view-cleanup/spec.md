## ADDED Requirements

### Requirement: Time-based pruning of stale views

The system SHALL periodically scan the `viewsDir` and remove view directories older than a configurable maximum age (default: 24 hours). The maximum age SHALL be configurable via environment variable or application configuration. The
cleanup scan interval is configurable (default: 60 minutes).

#### Scenario: Stale view removal

- **WHEN** the cleanup task runs and finds views older than the maximum age
- **THEN** those view directories are deleted

#### Scenario: Cleanup trigger on startup

- **WHEN** the MCP server starts
- **THEN** an initial cleanup scan is performed synchronously to remove stale views from previous sessions

### Requirement: Session-to-view mapping for cleanup (optional)

View directories contain a `manifest.json` with a `sessionId` field that identifies the session that created the view. The cleanup mechanism MAY use this field to correlate views with sessions, but time-based pruning is the primary
mechanism. Session tracking is best-effort.

**sessionId Purpose:** The `sessionId` is a UUID generated when a view is created. It serves two purposes:

1. **Correlation:** Enables cleanup to identify which session created a view (for optional session-end cleanup)
2. **Debugging:** Helps trace view lifecycle for debugging and logging

The `sessionId` does NOT represent an MCP server session — it is a view-scoped identifier for lifecycle management only.

#### Scenario: Session tracking via manifest (best-effort)

- **WHEN** cleanup runs and session tracking is enabled
- **THEN** it reads `sessionId` from each `manifest.json` to identify view ownership
- **IF** session tracking is unavailable, time-based pruning proceeds without session correlation

### Requirement: Cleanup trigger mechanism

The cleanup mechanism SHALL be triggered by:

1. A background scheduler running at a configurable interval (default: 60 minutes)
2. An initial synchronous scan on MCP server startup

#### Scenario: Periodic cleanup

- **WHEN** the background scheduler interval elapses
- **THEN** cleanup scans for stale views and removes them

### Requirement: Safe deletion with error handling

The cleanup process SHALL handle deletion errors gracefully (e.g., files in use on Windows) and log warnings without crashing. Failed deletions SHALL be retried on the next cleanup cycle.

#### Scenario: Deletion failure handled gracefully

- **WHEN** a view directory cannot be deleted (e.g., file in use)
- **THEN** a warning is logged and the directory is retried on the next cycle
