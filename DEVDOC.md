# Developer Documentation - Chat Logs Mod

## Overview

The Chat Logs mod is a Minecraft Fabric mod that persistently saves chat messages using a dual-storage approach. It captures all in-game chat messages and stores them in two concurrent formats:

1. **Plain text files (.txt)** - Human-readable session logs
2. **SQLite database** - Rich formatted messages with metadata for advanced querying

### Design Philosophy

- **Minimal invasiveness**: Uses very few mixins to maximize mod compatibility
- **Crash-resistant**: Automatic recovery for interrupted sessions
- **Thread-safe**: Background writing prevents game lag
- **Version-resistant**: Simple architecture reduces update requirements

---

## Core Concepts

### Sessions

A **session** represents a continuous period of gameplay from when the player connects to a server or opens a singleplayer world until they disconnect or quit.

#### Session Lifecycle

**Session Start Triggers:**
- **Multiplayer**: When connecting to a server ([ConnectScreenMixin.java:18-25](src/main/java/fr/raconteur/chatlogs/mixin/ConnectScreenMixin.java#L18-L25))
  - Triggered in `ConnectScreen.connect()`
  - Session name = server address (e.g., "play.hypixel.net")
  - `isMultiplayer = true`

- **Singleplayer**: When loading a world ([MinecraftClientMixin.java:34-48](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L34-L48))
  - Triggered in `MinecraftClient.startIntegratedServer()`
  - Session name = world directory name
  - `isMultiplayer = false`

**Active Session State:**
- Only one session can be active at a time
- Messages are queued in memory and written by background thread
- Both TXT file and SQLite database are updated concurrently

**Session End Triggers:**
- **Disconnection**: When player disconnects from world/server ([MinecraftClientMixin.java:17-25](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L17-L25))
- **Client shutdown**: Before Minecraft exits ([MinecraftClientMixin.java:27-32](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L27-L32))
- **New session start**: Previous session is automatically ended

#### Session Storage

Each session creates:
- **TXT file**: `chatlogs/{sessionName}_{timestamp}.txt`
- **SQLite record**: Entry in `sessions` table with unique ID
- **Lock file**: `.lock` file during active writing (auto-cleaned on close)
- **Unsaved marker**: Entry in `unsaved.marker` file (removed on graceful close)

### Messages

**What Gets Captured:**
- All messages added to the chat HUD via `ChatHud.addMessage()` ([ChatHudMixin.java:44-52](src/main/java/fr/raconteur/chatlogs/mixin/ChatHudMixin.java#L44-L52))
- Player chat messages
- System messages (join/leave, server announcements)
- Command feedback
- Death messages

**Message Structure:**

Each message contains:
- **Timestamp**: When the message was logged (milliseconds since epoch)
- **Plain text**: `Text.getString()` - flat string representation
- **JSON**: Full Minecraft Text component structure preserving:
  - Colors and formatting (bold, italic, etc.)
  - Hover events (tooltips)
  - Click events (commands, URLs)
- **Sender name**: Extracted via configurable regex patterns (optional, can be `null`)

**Sender Extraction:**

The sender name is extracted from the message text using regex patterns configured in [chatlogs-config.json](config/chatlogs-config.json). Patterns use a named capture group `(?<sender>...)`.

Default pattern: `^(?:\[[^\[\]]*\])?\s*<(?<sender>.*)>.*`
- Matches standard format: `[prefix] <PlayerName> message`
- Example: `<Alice> Hello!` → sender = "Alice"

Configuration supports session-specific patterns or falls back to "default" ([ChatLogsConfig.java:58-84](src/main/java/fr/raconteur/chatlogs/config/ChatLogsConfig.java#L58-L84)).

---

## Architecture & Class Overview

### Entry Point

**[ChatLogsMod.java](src/main/java/fr/raconteur/chatlogs/ChatLogsMod.java)**
- Implements `ModInitializer`
- Initialization sequence:
  1. Load configuration ([line 22](src/main/java/fr/raconteur/chatlogs/ChatLogsMod.java#L22))
  2. Initialize SQLite database ([line 31](src/main/java/fr/raconteur/chatlogs/ChatLogsMod.java#L31))
  3. Perform crash recovery ([line 40](src/main/java/fr/raconteur/chatlogs/ChatLogsMod.java#L40))

### Session Management

**[SimpleSessionRecorder.java](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java)**

The heart of the logging system. Manages the active session lifecycle.

**Key Responsibilities:**
- Creates and manages TXT log files with file locking
- Creates SQLite session records
- Runs background writer thread for async I/O
- Queues messages using `BlockingQueue<String>` for thread-safe buffering
- Handles graceful shutdown and crash recovery markers

**Important Methods:**
- `start(String sessionName, boolean isMultiplayer)` - Creates new session ([line 287](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L287))
- `current()` - Returns active session instance ([line 313](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L313))
- `end()` - Ends active session ([line 317](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L317))
- `logMessage(Text message)` - Queues message for writing ([line 324](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L324))

**Background Writing:**
- Dedicated daemon thread runs `writerLoop()` ([line 160](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L160))
- Batch processing: drains up to 100 messages per cycle for efficiency ([line 173](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L173))
- Graceful shutdown: waits up to 5 seconds for queue to flush ([line 364](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L364))

**File Locking:**
- Uses `FileLock` to prevent concurrent access ([line 131](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L131))
- Orphaned locks are cleaned up on startup ([line 302](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L302))

### Database Layer

**[AbstractDatabase.java](src/main/java/fr/raconteur/chatlogs/database/AbstractDatabase.java)**

Provides core database infrastructure for any SQLite database used by the mod.

**Key Features:**
- **Schema Versioning**: Automatic migration system using numbered SQL files ([line 97](src/main/java/fr/raconteur/chatlogs/database/AbstractDatabase.java#L97))
  - Migration files: `src/main/resources/database/{dbName}/v{N}.sql`
  - Tracked in `db_version` table
- **Automatic Backups**: Creates backup before each migration ([line 195](src/main/java/fr/raconteur/chatlogs/database/AbstractDatabase.java#L195))
  - Stored in `chatlogs/backups/{dbName}/`
- **Inter-Process Locking**: Prevents multiple Minecraft instances from accessing same DB ([line 288](src/main/java/fr/raconteur/chatlogs/database/AbstractDatabase.java#L288))
- **Transaction Management**: Helper methods for ACID operations ([line 219](src/main/java/fr/raconteur/chatlogs/database/AbstractDatabase.java#L219))

**[SessionDatabase.java](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java)**

Manages the `sessions.db` SQLite database - the concrete implementation of the database layer.

**Database Schema** ([v1.sql](src/main/resources/database/sessions/v1.sql)):

```sql
sessions:
  - id (PRIMARY KEY)
  - session_name (server/world name)
  - start_time, end_time (millisecond timestamps)
  - is_multiplayer (boolean)
  - txt_file_path (reference to .txt file)
  - message_count (cached count)
  - created_at, updated_at

messages:
  - id (PRIMARY KEY)
  - session_id (FOREIGN KEY → sessions.id)
  - sender_name (extracted via regex, nullable)
  - message_text (plain text)
  - message_json (full Text component JSON)
  - timestamp (message timestamp)
  - created_at
```

**Key Operations:**
- `createSession()` - Creates session record, returns ID ([line 71](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L71))
- `addMessage()` - Adds single message ([line 107](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L107))
- `addMessagesBulk()` - Batch insert for performance ([line 139](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L139))
- `endSession()` - Sets end_time ([line 176](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L176))
- `getAllSessions()` - Query all sessions ([line 203](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L203))
- `getMessagesForSession()` - Query messages for session ([line 237](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L237))

**Thread Safety:**
- Uses `ReentrantReadWriteLock` for concurrent access ([line 19](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L19))
- Prepared statements cached for performance ([line 22-25](src/main/java/fr/raconteur/chatlogs/database/SessionDatabase.java#L22-L25))

### Configuration

**[ChatLogsConfig.java](src/main/java/fr/raconteur/chatlogs/config/ChatLogsConfig.java)**

Manages `config/chatlogs-config.json` - regex patterns for extracting sender names from messages.

**Configuration Format:**
```json
{
  "default": [
    "^(?:\\[[^\\[\\]]*\\])?\\s*<(?<sender>.*)>.*"
  ],
  "play.example.com": [
    "^\\[(?<sender>\\w+)\\].*"
  ]
}
```

**Key Methods:**
- `extractSenderName(sessionName, messageText)` - Applies regex patterns ([line 58](src/main/java/fr/raconteur/chatlogs/config/ChatLogsConfig.java#L58))
- Pattern compilation is cached for performance ([line 202](src/main/java/fr/raconteur/chatlogs/config/ChatLogsConfig.java#L202))

### Recovery System

**[CrashRecovery.java](src/main/java/fr/raconteur/chatlogs/session/CrashRecovery.java)**

Handles recovery of sessions that were interrupted by crashes or forced shutdowns.

**Recovery Process** (runs during mod initialization):

1. **SQLite Session Recovery** ([line 226](src/main/java/fr/raconteur/chatlogs/session/CrashRecovery.java#L226)):
   - Finds sessions with `end_time = 0` (unclosed)
   - Sets end_time to current timestamp

2. **TXT File Recovery** ([line 27](src/main/java/fr/raconteur/chatlogs/session/CrashRecovery.java#L27)):
   - Reads `unsaved.marker` file for interrupted files
   - Appends crash notice to each file ([line 92](src/main/java/fr/raconteur/chatlogs/session/CrashRecovery.java#L92))
   - Removes marker file

**Unsaved Marker Format:**
- Simple text file with one absolute file path per line
- Updated when session starts ([SimpleSessionRecorder.java:247](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L247))
- Entry removed on graceful close ([SimpleSessionRecorder.java:255](src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java#L255))

### Mixins (Injection Points)

Mixins inject code into vanilla Minecraft methods to hook into game events.

**[ChatHudMixin.java](src/main/java/fr/raconteur/chatlogs/mixin/ChatHudMixin.java)**
- **Target**: `ChatHud.addMessage()` method
- **Purpose**: Capture all chat messages
- **Injection**: `@Inject` at `HEAD` ([line 44](src/main/java/fr/raconteur/chatlogs/mixin/ChatHudMixin.java#L44))
- **Special handling**: ChatPatches mod compatibility check ([line 24](src/main/java/fr/raconteur/chatlogs/mixin/ChatHudMixin.java#L24))
  - Uses reflection to detect when ChatPatches is restoring history
  - Avoids duplicate logging of restored messages

**[ConnectScreenMixin.java](src/main/java/fr/raconteur/chatlogs/mixin/ConnectScreenMixin.java)**
- **Target**: `ConnectScreen.connect()` method
- **Purpose**: Start session when connecting to multiplayer server
- **Injection**: `@Inject` at `HEAD` ([line 18](src/main/java/fr/raconteur/chatlogs/mixin/ConnectScreenMixin.java#L18))

**[MinecraftClientMixin.java](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java)**
- **Target 1**: `MinecraftClient.disconnect()` - End session on disconnect ([line 17](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L17))
- **Target 2**: `MinecraftClient.stop()` - End session before exit ([line 27](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L27))
- **Target 3**: `MinecraftClient.startIntegratedServer()` - Start singleplayer session ([line 34](src/main/java/fr/raconteur/chatlogs/mixin/MinecraftClientMixin.java#L34))

---

## Data Flow

### Message Capture to Storage

```
1. Chat message appears in game
   ↓
2. ChatHudMixin.onMessage() intercepts
   ↓
3. SimpleSessionRecorder.logMessage() called
   ↓
4a. Message queued in BlockingQueue          4b. Message saved to SQLite immediately
    ↓                                             ↓
5a. Background writer thread pulls from queue    - Extract sender via regex
    ↓                                             - Serialize Text to JSON
6a. Batch write to TXT file                      - Insert into messages table
    ↓                                             - Update message_count
7a. File flushed to disk
```

### Thread Safety

**TXT Writing:**
- Main thread: Adds to `BlockingQueue` (lock-free, non-blocking)
- Writer thread: Reads from queue, writes to file with `FileLock`
- On shutdown: Writer thread waits up to 5s for queue to drain

**SQLite Writing:**
- Direct writes from main thread (synchronous)
- `ReentrantReadWriteLock` prevents concurrent access
- Transactions ensure ACID properties

### Crash Resistance

**Markers:**
- `unsaved.marker`: Tracks files being written
- `.lock` files: Prevent concurrent access
- Both cleaned up on graceful shutdown
- Both detected and handled during recovery

---

## File Structure

### Storage Locations

All chat logs are stored in the `chatlogs/` folder at the game root:

```
chatlogs/
├── sessions.db                           # SQLite database
├── sessions.db.lock                      # Database lock file (temporary)
├── unsaved.marker                        # Crash recovery marker (temporary)
├── {sessionName}_{timestamp}.txt         # Session log files
├── {sessionName}_{timestamp}.txt.lock    # File locks (temporary)
└── backups/
    └── sessions/
        └── sessions_v0_20250101_120000.db  # Pre-migration backups
```

### Naming Conventions

**TXT Files:**
- Format: `{sessionName}_{timestamp}.txt`
- Session name: Sanitized (non-alphanumeric → underscore)
- Timestamp: `yyyy-MM-dd_HH-mm-ss`
- Example: `play_hypixel_net_2025-01-15_14-30-22.txt`

**Lock Files:**
- Same name as data file + `.lock` extension
- Auto-deleted on graceful close
- Cleaned up on next startup if orphaned

---

## Future Development Notes

### Adding Database Migrations

To evolve the database schema:

1. Create new SQL file: `src/main/resources/database/sessions/v{N}.sql`
   - `{N}` = next version number (e.g., v2, v3)
2. Write migration SQL (can include multiple statements separated by `;`)
3. On mod initialization, `AbstractDatabase` will:
   - Detect new version
   - Create automatic backup
   - Apply migration
   - Record in `db_version` table

**Example v2 migration:**
```sql
-- Add player_uuid column to messages
ALTER TABLE messages ADD COLUMN player_uuid TEXT;
CREATE INDEX idx_messages_uuid ON messages (player_uuid);
```

### Extending Regex Patterns

To customize sender extraction for specific servers:

1. Edit `config/chatlogs-config.json`
2. Add server-specific pattern with session name as key:
```json
{
  "default": ["^(?:\\[[^\\[\\]]*\\])?\\s*<(?<sender>.*)>.*"],
  "mc.custom-server.net": [
    "^\\[Rank\\] (?<sender>\\w+):.*",
    "^(?<sender>\\w+) >>.*"
  ]
}
```
3. Patterns are tried in order; first match wins
4. Fallback to "default" if no session-specific pattern matches

### About the "backup" Directory

The `backup/` directory in the source code contains reference implementation from the original [Permanent Chatlogs mod](https://modrinth.com/mod/permanent-chatlogs) that inspired this project.

**Purpose:**
- Code reference during development
- **Not used** by the current mod implementation
- **Should be removed** when no longer needed for development reference

This is **separate** from `chatlogs/backups/` which stores database backup files.

---

## External Tools

**[log_gui.py](log_gui.py)** - Experimental Python GUI for browsing the SQLite database

- Connects to `chatlogs/sessions.db`
- Displays sessions with timestamps and message counts
- Renders messages with Minecraft color codes
- Shows hover tooltips from message JSON
- Provides search and filtering capabilities

This demonstrates how external tools can leverage the rich metadata stored in the database.
