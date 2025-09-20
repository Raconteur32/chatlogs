-- Initial database schema for sessions and messages
-- Version 1: Basic chat logging functionality

-- Table principale des sessions de chat
CREATE TABLE sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_name TEXT NOT NULL,           -- Nom du serveur/monde
    start_time INTEGER NOT NULL,          -- Timestamp début session
    end_time INTEGER,                     -- Timestamp fin session (NULL si en cours)
    is_multiplayer BOOLEAN NOT NULL DEFAULT 0,
    txt_file_path TEXT NOT NULL,          -- Chemin vers le fichier .txt correspondant
    message_count INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Table des messages - SEULEMENT les messages de chat normaux
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    sender_name TEXT,                     -- Nom du joueur extrait via regex (peut être NULL pour messages système)
    message_text TEXT NOT NULL,           -- Texte plain (.getString())
    message_json TEXT,                    -- JSON complet du composant Text (pour préserver tooltips)
    timestamp INTEGER NOT NULL,           -- Timestamp du message
    created_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
);

-- Index pour les performances
CREATE INDEX idx_messages_session_id ON messages (session_id);
CREATE INDEX idx_messages_timestamp ON messages (timestamp);
CREATE INDEX idx_messages_sender_name ON messages (sender_name);
CREATE INDEX idx_messages_text_search ON messages (message_text);
CREATE INDEX idx_sessions_start_time ON sessions (start_time);

-- Vue pour faciliter les requêtes courantes
CREATE VIEW session_summary AS
SELECT 
    s.id,
    s.session_name,
    s.start_time,
    s.end_time,
    s.is_multiplayer,
    s.message_count,
    s.txt_file_path,
    datetime(s.start_time/1000, 'unixepoch') as start_time_readable,
    datetime(s.end_time/1000, 'unixepoch') as end_time_readable,
    CASE 
        WHEN s.end_time IS NULL THEN 'En cours'
        ELSE 'Terminée'
    END as status
FROM sessions s;