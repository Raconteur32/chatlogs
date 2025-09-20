package fr.raconteur.chatlogs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import fr.raconteur.chatlogs.ChatLogsMod;
import net.minecraft.util.Util;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ChatLogsConfig {
    private static final String CONFIG_FILE_NAME = "chatlogs-config.json";
    
    // Config folder logic similar to CHATLOG_FOLDER
    public static final File CONFIG_FOLDER = Util.make(() -> {
        File f = new File("config");
        if (!f.exists()) {
            f.mkdirs();
        }
        return f;
    });
    
    private static final File CONFIG_FILE = new File(CONFIG_FOLDER, CONFIG_FILE_NAME);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private static ChatLogsConfig instance;
    private Map<String, List<String>> senderRegexPatterns;
    private Map<String, List<Pattern>> compiledPatterns; // Cache for compiled patterns
    
    private ChatLogsConfig() {
        this.senderRegexPatterns = new HashMap<>();
        this.compiledPatterns = new HashMap<>();
        loadConfig();
    }
    
    public static ChatLogsConfig getInstance() {
        if (instance == null) {
            instance = new ChatLogsConfig();
        }
        return instance;
    }
    
    /**
     * Extract sender name from message text using configured regex patterns
     */
    public String extractSenderName(String sessionName, String messageText) {
        List<Pattern> patterns = getCompiledPatternsForSession(sessionName);
        
        for (Pattern pattern : patterns) {
            try {
                Matcher matcher = pattern.matcher(messageText);
                if (matcher.find()) {
                    // Look for named group "sender"
                    try {
                        String sender = matcher.group("sender");
                        if (sender != null && !sender.trim().isEmpty()) {
                            return sender.trim();
                        }
                    } catch (IllegalArgumentException e) {
                        // Named group "sender" not found in pattern
                        ChatLogsMod.LOGGER.warn("Pattern '{}' matched but has no 'sender' group: {}", 
                                              pattern.pattern(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                ChatLogsMod.LOGGER.error("Error applying regex pattern '{}' to message '{}': {}", 
                                       pattern.pattern(), messageText, e.getMessage());
            }
        }
        
        return null; // No pattern matched
    }
    
    /**
     * Get compiled patterns for a session, with fallback to "default"
     */
    private List<Pattern> getCompiledPatternsForSession(String sessionName) {
        // Try exact session name first
        List<Pattern> patterns = compiledPatterns.get(sessionName);
        if (patterns != null && !patterns.isEmpty()) {
            return patterns;
        }
        
        // Fallback to "default"
        patterns = compiledPatterns.get("default");
        if (patterns != null && !patterns.isEmpty()) {
            return patterns;
        }
        
        // Emergency fallback - return empty list
        ChatLogsMod.LOGGER.warn("No regex patterns found for session '{}' or 'default'", sessionName);
        return new ArrayList<>();
    }
    
    /**
     * Load configuration from file, create default if needed
     */
    private void loadConfig() {
        if (!CONFIG_FILE.exists()) {
            createDefaultConfig();
            return;
        }
        
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
            Map<String, List<String>> loadedConfig = GSON.fromJson(reader, type);
            
            if (loadedConfig == null || !isValidConfig(loadedConfig)) {
                ChatLogsMod.LOGGER.warn("Invalid config format in {}, recreating with defaults", CONFIG_FILE_NAME);
                createDefaultConfig();
                return;
            }
            
            this.senderRegexPatterns = loadedConfig;
            compilePatterns();
            
            ChatLogsMod.LOGGER.info("Loaded chat logs config with {} session pattern groups", 
                                  senderRegexPatterns.size());
            
        } catch (IOException | JsonSyntaxException e) {
            ChatLogsMod.LOGGER.error("Failed to load config from {}, recreating with defaults: {}", 
                                   CONFIG_FILE_NAME, e.getMessage());
            createDefaultConfig();
        }
    }
    
    /**
     * Validate that config is a map of string -> list of strings
     */
    private boolean isValidConfig(Map<String, List<String>> config) {
        if (config.isEmpty()) {
            return false;
        }
        
        for (Map.Entry<String, List<String>> entry : config.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                return false;
            }
            
            // Check that all values in list are strings (not null)
            for (String pattern : entry.getValue()) {
                if (pattern == null) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Create default configuration file
     */
    private void createDefaultConfig() {
        Map<String, List<String>> defaultConfig = new HashMap<>();
        List<String> defaultPatterns = new ArrayList<>();
        
        // Default regex pattern for standard chat format: [prefix] <PlayerName> message
        defaultPatterns.add("^(?:\\[[^\\[\\]]*\\])?\\s*<(?<sender>.*)>.*");
        
        defaultConfig.put("default", defaultPatterns);
        this.senderRegexPatterns = defaultConfig;
        
        saveConfig();
        compilePatterns();
        
        ChatLogsMod.LOGGER.info("Created default chat logs config at {}", CONFIG_FILE.getPath());
    }
    
    /**
     * Save current configuration to file
     */
    private void saveConfig() {
        try {
            // Ensure parent directory exists
            CONFIG_FILE.getParentFile().mkdirs();
            
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(senderRegexPatterns, writer);
            }
            
        } catch (IOException e) {
            ChatLogsMod.LOGGER.error("Failed to save config to {}: {}", CONFIG_FILE_NAME, e.getMessage());
        }
    }
    
    /**
     * Compile all regex patterns and cache them
     */
    private void compilePatterns() {
        compiledPatterns.clear();
        
        for (Map.Entry<String, List<String>> entry : senderRegexPatterns.entrySet()) {
            String sessionKey = entry.getKey();
            List<String> patternStrings = entry.getValue();
            List<Pattern> compiled = new ArrayList<>();
            
            for (String patternString : patternStrings) {
                try {
                    Pattern pattern = Pattern.compile(patternString);
                    compiled.add(pattern);
                } catch (PatternSyntaxException e) {
                    ChatLogsMod.LOGGER.error("Invalid regex pattern for session '{}': '{}' - {}", 
                                           sessionKey, patternString, e.getMessage());
                }
            }
            
            if (!compiled.isEmpty()) {
                compiledPatterns.put(sessionKey, compiled);
                ChatLogsMod.LOGGER.debug("Compiled {} regex patterns for session '{}'", 
                                       compiled.size(), sessionKey);
            }
        }
    }
    
    /**
     * Get raw configuration (for debugging/inspection)
     */
    public Map<String, List<String>> getRawConfig() {
        return new HashMap<>(senderRegexPatterns);
    }
}