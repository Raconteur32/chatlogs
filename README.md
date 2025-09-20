# Chat Logs

A lightweight Minecraft mod for saving chat logs in a readable format with minimal interference to the base game.

## Inspiration

While this mod shares virtually no code with the original project, it is heavily inspired by [Permanent Chatlogs](https://modrinth.com/mod/permanent-chatlogs), which offers an alternative approach to saving chat logs separately from the main game logs.

## Philosophy

This mod aims to make chat logs readable while interfering as little as possible with the base game mechanics. It uses very few mixins that inject code into vanilla methods, maximizing compatibility with other mods and reducing the likelihood of being broken by game updates. This is a significant advantage for modpacks, as the mod will generally not need updates when new versions of Minecraft are released.

## Features

The mod uses two complementary approaches to save chat logs:

### 1. Session-based TXT Files
- **Simple text format**: Each session is saved as a plain `.txt` file
- **Session definition**: A session spans from connection to a server/singleplayer world until disconnection
- **Easy to read**: Human-readable format that can be opened with any text editor
- **Reliable**: Crash-resistant with automatic recovery mechanisms

### 2. Detailed Database Storage (Experimental)
- **Rich formatting preservation**: Saves complete text formatting details in an SQLite database
- **Enhanced search capabilities**: Enables advanced searching and filtering of chat history
- **External GUI interface**: Provides a dedicated graphical interface for better reading experience
- **JSON preservation**: Maintains original Minecraft Text component structure with colors, hover events, and styling

## External Interface

An experimental Python GUI is available for browsing the database logs with full formatting support:

🔗 **[Chat Log GUI (Experimental)](https://github.com/Raconteur32/chatlogs/blob/main/log_gui.py)**

This interface provides:
- Session browsing with message counts and timestamps
- Full Minecraft color code support
- Interactive tooltips for hover events
- Copy-to-clipboard functionality

