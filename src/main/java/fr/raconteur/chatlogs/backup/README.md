# Backup of Original Permanent-Chatlogs Features

This directory contains backups of all the original mod features that were removed during simplification to create a lightweight chat logging version.

## Backed up packages:

- **gui/** - All GUI screens and UI components for viewing/managing sessions
- **export/** - HTML and TXT export functionality  
- **i18n/** - Internationalization support for UI elements
- **config/** - Complex configuration system with many options
- **session/** - Original complex session logic (JSON storage, compression, search)
- **mixin/** - All removed mixins (UI-related, visual modifications)
- **util/** - Utility classes (TextEventContentExtractor, etc.)
- **chatlogs.mixins.json** - Original mixins configuration

## ⚠️ Important Notes:

- **All imports fixed**: Package declarations and imports updated to `fr.raconteur.chatlogs.backup.*`
- **Compilable backup**: This backup should compile independently if needed
- **Self-contained**: All cross-references point within the backup package

## New simplified implementation:

The mod now uses only:
- `SimpleSessionRecorder.java` - Simple .txt logging with robust save logic
- `CrashRecovery.java` - Crash detection and file recovery
- 3 mixins: ChatHudMixin, MinecraftClientMixin, ConnectScreenMixin

## Goals achieved:

✅ Simple .txt log files per session  
✅ Robust file locking and crash recovery  
✅ No UI, export, or visual modifications  
✅ Lightweight and efficient  
✅ Complete backup of original functionality

## Backup date:
Created during mod simplification process - all original features preserved.