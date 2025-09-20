#!/usr/bin/env python3
import sqlite3
import json
import sys
import tkinter as tk
from tkinter import ttk
from tktooltip import ToolTip
from datetime import datetime
from typing import Dict, List, Optional, Any

class LogGUI:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self.conn = sqlite3.connect(db_path)
        self.conn.row_factory = sqlite3.Row
        
        # Minecraft color mapping
        self.minecraft_colors = {
            'black': '#000000',
            'dark_blue': '#0000AA',
            'dark_green': '#00AA00',
            'dark_aqua': '#00AAAA',
            'dark_red': '#AA0000',
            'dark_purple': '#AA00AA',
            'gold': '#FFAA00',
            'gray': '#AAAAAA',
            'dark_gray': '#555555',
            'blue': '#5555FF',
            'green': '#55FF55',
            'aqua': '#55FFFF',
            'red': '#FF5555',
            'light_purple': '#FF55FF',
            'yellow': '#FFFF55',
            'white': '#FFFFFF'
        }
        
        # Minecraft color codes mapping (without 'r' - reset is handled specially)
        self.minecraft_color_codes = {
            '0': '#000000',  # black
            '1': '#0000AA',  # dark_blue
            '2': '#00AA00',  # dark_green
            '3': '#00AAAA',  # dark_aqua
            '4': '#AA0000',  # dark_red
            '5': '#AA00AA',  # dark_purple
            '6': '#FFAA00',  # gold
            '7': '#AAAAAA',  # gray
            '8': '#555555',  # dark_gray
            '9': '#5555FF',  # blue
            'a': '#55FF55',  # green
            'b': '#55FFFF',  # aqua
            'c': '#FF5555',  # red
            'd': '#FF55FF',  # light_purple
            'e': '#FFFF55',  # yellow
            'f': '#FFFFFF'   # white
        }
        
        self.setup_ui()
        self.load_sessions()
    
    def setup_ui(self):
        """Setup the main UI"""
        self.root = tk.Tk()
        self.root.title("Chat Log Session Viewer")
        self.root.geometry("1200x800")
        self.root.configure(bg='#2D2D2D')
        
        # Main layout
        main_frame = tk.Frame(self.root, bg='#2D2D2D')
        main_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        # Session list on the left
        left_frame = tk.Frame(main_frame, bg='#2D2D2D', width=250)
        left_frame.pack(side=tk.LEFT, fill=tk.Y, padx=(0, 10))
        left_frame.pack_propagate(False)
        
        tk.Label(left_frame, text="Sessions", bg='#2D2D2D', fg='white', font=("Arial", 12, "bold")).pack(pady=(0, 10))
        
        # Session listbox with scrollbar
        listbox_frame = tk.Frame(left_frame, bg='#2D2D2D')
        listbox_frame.pack(fill=tk.BOTH, expand=True)
        
        scrollbar = tk.Scrollbar(listbox_frame)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        self.session_listbox = tk.Listbox(
            listbox_frame,
            yscrollcommand=scrollbar.set,
            bg='#404040',
            fg='white',
            selectbackground='#505050',
            font=("Consolas", 9)
        )
        self.session_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.config(command=self.session_listbox.yview)
        
        self.session_listbox.bind('<<ListboxSelect>>', self.on_session_select)
        
        # Messages panel on the right
        right_frame = tk.Frame(main_frame, bg='#2D2D2D')
        right_frame.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True)
        
        tk.Label(right_frame, text="Messages", bg='#2D2D2D', fg='white', font=("Arial", 12, "bold")).pack(pady=(0, 10))
        
        # Scrollable message area
        self.canvas = tk.Canvas(right_frame, bg='black', highlightthickness=0)
        self.scrollbar_right = tk.Scrollbar(right_frame, orient="vertical", command=self.canvas.yview)
        self.scrollable_frame = tk.Frame(self.canvas, bg='black')
        
        self.scrollable_frame.bind(
            "<Configure>",
            lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        )
        
        self.canvas.create_window((0, 0), window=self.scrollable_frame, anchor="nw")
        self.canvas.configure(yscrollcommand=self.scrollbar_right.set)
        
        self.canvas.pack(side="left", fill="both", expand=True)
        self.scrollbar_right.pack(side="right", fill="y")
        
        # Mouse wheel scrolling
        self.canvas.bind("<MouseWheel>", self._on_mousewheel)
        
        # Data storage
        self.sessions_data = []
        self.current_messages = []
    
    def _on_mousewheel(self, event):
        """Handle mouse wheel scrolling"""
        self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")
    
    def load_sessions(self):
        """Load sessions from database"""
        cursor = self.conn.execute("""
            SELECT s.*, COUNT(m.id) as message_count
            FROM sessions s
            LEFT JOIN messages m ON s.id = m.session_id
            GROUP BY s.id
            ORDER BY s.start_time DESC
        """)
        
        self.session_listbox.delete(0, tk.END)
        self.sessions_data = []
        
        for row in cursor:
            session = {
                'id': row['id'],
                'name': row['session_name'] or "Unnamed Session",
                'start_time': datetime.fromtimestamp(row['start_time'] / 1000) if row['start_time'] else None,
                'is_multiplayer': bool(row['is_multiplayer']) if row['is_multiplayer'] is not None else False,
                'message_count': row['message_count'] or 0
            }
            
            self.sessions_data.append(session)
            
            # Format for display
            session_type = "MP" if session['is_multiplayer'] else "SP"
            start_time = session['start_time'].strftime("%m/%d %H:%M") if session['start_time'] else "Unknown"
            display_text = f"[{session_type}] {session['name']} ({session['message_count']} msgs) - {start_time}"
            
            self.session_listbox.insert(tk.END, display_text)
    
    def on_session_select(self, event):
        """Handle session selection"""
        selection = self.session_listbox.curselection()
        if not selection:
            return
        
        session_index = selection[0]
        session = self.sessions_data[session_index]
        self.load_messages(session['id'])
    
    def load_messages(self, session_id: int):
        """Load messages for selected session"""
        cursor = self.conn.execute("""
            SELECT * FROM messages 
            WHERE session_id = ? 
            ORDER BY timestamp ASC
        """, (session_id,))
        
        # Clear previous messages
        for widget in self.scrollable_frame.winfo_children():
            widget.destroy()
        
        self.current_messages = []
        
        for row in cursor:
            message_data = {
                'id': row['id'],
                'timestamp': datetime.fromtimestamp(row['timestamp'] / 1000),
                'sender_name': row['sender_name'],
                'message_text': row['message_text'],
                'message_json': json.loads(row['message_json']) if row['message_json'] else None
            }
            
            self.current_messages.append(message_data)
            self.create_message_widget(message_data)
    
    def create_message_widget(self, message_data: Dict):
        """Create widget for a single message with simple hover detection"""
        # Main message container - this will be our single hover detection area
        message_frame = tk.Frame(self.scrollable_frame, bg='black', cursor='arrow')
        message_frame.pack(fill=tk.X, pady=1, padx=5)
        
        # Content area
        content_frame = tk.Frame(message_frame, bg='black')
        content_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        # Timestamp and sender (avoid duplication if JSON already contains sender)
        timestamp_str = message_data['timestamp'].strftime("%H:%M:%S")
        
        # Check if JSON already contains the sender name
        json_contains_sender = False
        if message_data['message_json'] and message_data['sender_name']:
            # Check if the plain text starts with the sender pattern
            if message_data['message_text'].startswith(f"<{message_data['sender_name']}>"):
                json_contains_sender = True
        
        if message_data['sender_name'] and not json_contains_sender:
            prefix = f"[{timestamp_str}] <{message_data['sender_name']}> "
            prefix_color = '#55FF55'  # Green for player messages
        else:
            prefix = f"[{timestamp_str}] "
            prefix_color = '#AAAAAA'  # Gray for timestamp only
        
        # Create prefix label
        prefix_label = tk.Label(
            content_frame,
            text=prefix,
            font=("Consolas", 10),
            bg='black',
            fg=prefix_color,
            anchor='w'
        )
        prefix_label.pack(side=tk.LEFT)
        
        # Add tooltip to timestamp
        time_tooltip = f"Full time: {message_data['timestamp'].strftime('%Y-%m-%d %H:%M:%S')}"
        ToolTip(prefix_label, msg=time_tooltip, delay=0.0, follow=True)
        
        # Store all labels for background updates
        all_labels = [prefix_label]
        
        # Parse and create message content
        if message_data['message_json']:
            json_labels = self.create_json_content(content_frame, message_data['message_json'])
            all_labels.extend(json_labels)
        else:
            # Plain text fallback
            text_label = tk.Label(
                content_frame,
                text=message_data['message_text'],
                font=("Consolas", 10),
                bg='black',
                fg='white',
                anchor='w'
            )
            text_label.pack(side=tk.LEFT)
            all_labels.append(text_label)
        
        # Copy button (initially hidden)
        copy_button = tk.Button(
            message_frame,
            text="📋",
            font=("Arial", 8),
            bg='#404040',
            fg='white',
            activebackground='#505050',
            activeforeground='white',
            relief=tk.FLAT,
            borderwidth=0,
            padx=2,
            pady=1,
            cursor='hand2',
            command=lambda: self.copy_displayed_message_to_clipboard(message_data, all_labels)
        )
        copy_button.pack(side=tk.RIGHT, padx=(5, 0))
        copy_button.pack_forget()  # Hide initially
        
        # Add tooltip to copy button
        ToolTip(copy_button, msg="Copy message to clipboard", delay=0.0, follow=True)
        
        # Hover state management
        hover_active = {'state': False}
        
        def activate_hover():
            if not hover_active['state']:
                hover_active['state'] = True
                # Update all backgrounds
                message_frame.configure(bg='#1a1a1a')
                content_frame.configure(bg='#1a1a1a')
                for label in all_labels:
                    try:
                        label.configure(bg='#1a1a1a')
                    except tk.TclError:
                        pass
                copy_button.pack(side=tk.RIGHT, padx=(5, 0))
            
        def deactivate_hover():
            if hover_active['state']:
                hover_active['state'] = False
                # Reset all backgrounds
                message_frame.configure(bg='black')
                content_frame.configure(bg='black')
                for label in all_labels:
                    try:
                        label.configure(bg='black')
                    except tk.TclError:
                        pass
                copy_button.pack_forget()
        
        def on_enter(event):
            activate_hover()
            
        def on_leave(event):
            # Only deactivate if mouse is not over the copy button
            # Use after_idle to let the button's enter event fire first
            message_frame.after_idle(lambda: deactivate_hover() if not hover_active.get('button_hover', False) else None)
        
        def on_button_enter(event):
            hover_active['button_hover'] = True
            activate_hover()
            
        def on_button_leave(event):
            hover_active['button_hover'] = False
            # Small delay to prevent flicker when moving between frame and button
            message_frame.after(50, lambda: deactivate_hover() if not hover_active.get('button_hover', False) else None)
        
        # Bind hover events to message frame
        message_frame.bind("<Enter>", on_enter)
        message_frame.bind("<Leave>", on_leave)
        
        # Bind hover events to copy button to maintain hover state
        copy_button.bind("<Enter>", on_button_enter)
        copy_button.bind("<Leave>", on_button_leave)
    
    def parse_minecraft_color_codes(self, text: str, parent: tk.Frame, inherited_color: str) -> List[tk.Label]:
        """Parse § color codes in text and create multiple labels with appropriate colors"""
        labels = []
        if not text:
            return labels
            
        # Split by § to find color codes
        parts = text.split('§')
        
        # First part has no color code
        if parts[0]:
            label = tk.Label(
                parent,
                text=parts[0],
                font=("Consolas", 10),
                bg='black',
                fg=inherited_color,
                anchor='w'
            )
            label.pack(side=tk.LEFT)
            labels.append(label)
        
        # Process parts with color codes
        current_color = inherited_color
        default_color = inherited_color  # Remember the original color for §r
        
        for part in parts[1:]:
            if part:
                # First character is the color code
                color_code = part[0].lower()
                text_content = part[1:]
                
                if color_code == 'r':
                    # Reset to the original inherited color (component's default color)
                    current_color = default_color
                elif color_code in self.minecraft_color_codes:
                    current_color = self.minecraft_color_codes[color_code]
                
                if text_content:
                    label = tk.Label(
                        parent,
                        text=text_content,
                        font=("Consolas", 10),
                        bg='black',
                        fg=current_color,
                        anchor='w'
                    )
                    label.pack(side=tk.LEFT)
                    labels.append(label)
        
        return labels

    def create_json_content(self, parent: tk.Frame, json_data: Any) -> List[tk.Label]:
        """Create labels from JSON data and return list of labels created"""
        created_labels = []
        
        def process_component(component, inherited_color='white'):
            if isinstance(component, str) and component.strip():
                # Check if string contains § color codes
                if '§' in component:
                    color_labels = self.parse_minecraft_color_codes(component, parent, inherited_color)
                    created_labels.extend(color_labels)
                else:
                    # Simple string - create label
                    label = tk.Label(
                        parent,
                        text=component,
                        font=("Consolas", 10),
                        bg='black',
                        fg=inherited_color,
                        anchor='w'
                    )
                    label.pack(side=tk.LEFT)
                    created_labels.append(label)
                
            elif isinstance(component, dict):
                # Get text and color
                text = component.get('text', '')
                color = component.get('color', inherited_color)
                
                # Convert color name to hex
                if color in self.minecraft_colors:
                    color = self.minecraft_colors[color]
                elif not color.startswith('#'):
                    color = inherited_color
                
                # Create label if has text
                if text:
                    # Check if text contains § color codes
                    if '§' in text:
                        color_labels = self.parse_minecraft_color_codes(text, parent, color)
                        created_labels.extend(color_labels)
                        
                        # Add hover tooltip to all labels if present
                        hover_event = component.get('hoverEvent') or component.get('hover_event')
                        if hover_event:
                            tooltip_text = self.extract_tooltip_text(hover_event)
                            if tooltip_text:
                                for label in color_labels:
                                    ToolTip(label, msg=tooltip_text, delay=0.0, follow=True)
                    else:
                        label = tk.Label(
                            parent,
                            text=text,
                            font=("Consolas", 10),
                            bg='black',
                            fg=color,
                            anchor='w'
                        )
                        label.pack(side=tk.LEFT)
                        created_labels.append(label)
                        
                        # Add hover tooltip if present
                        hover_event = component.get('hoverEvent') or component.get('hover_event')
                        if hover_event:
                            tooltip_text = self.extract_tooltip_text(hover_event)
                            if tooltip_text:
                                ToolTip(label, msg=tooltip_text, delay=0.0, follow=True)
                
                # Process extra components
                extra = component.get('extra', [])
                for extra_component in extra:
                    process_component(extra_component, color)
        
        process_component(json_data)
        return created_labels
    
    def extract_tooltip_text(self, hover_event: Dict) -> Optional[str]:
        """Extract tooltip text from hover event"""
        if not isinstance(hover_event, dict):
            return None
        
        action = hover_event.get('action')
        if action == 'show_text':
            content = hover_event.get('contents') or hover_event.get('value', '')
            if isinstance(content, dict):
                return self.extract_text_from_json(content)
            return str(content)
        elif action == 'show_item':
            item_data = hover_event.get('contents') or hover_event.get('value', {})
            if isinstance(item_data, dict):
                item_id = item_data.get('id', 'Unknown')
                return f"Item: {item_id}"
        elif action == 'show_entity':
            entity_data = hover_event.get('contents') or hover_event.get('value', {})
            if isinstance(entity_data, dict):
                entity_type = entity_data.get('type', 'Unknown')
                return f"Entity: {entity_type}"
        
        return None
    
    def extract_text_from_json(self, json_data: Any) -> str:
        """Extract plain text from JSON component"""
        if isinstance(json_data, str):
            return json_data
        elif isinstance(json_data, dict):
            text = json_data.get('text', '')
            extra = json_data.get('extra', [])
            for extra_component in extra:
                text += self.extract_text_from_json(extra_component)
            return text
        elif isinstance(json_data, list):
            return ''.join(self.extract_text_from_json(item) for item in json_data)
        return str(json_data)
    
    def copy_displayed_message_to_clipboard(self, message_data: Dict, all_labels: List[tk.Label]):
        """Copy the exact text as displayed in the GUI"""
        # Extract text from all labels in display order
        displayed_text = ""
        
        for label in all_labels:
            try:
                label_text = label.cget("text")
                displayed_text += label_text
            except tk.TclError:
                # Skip labels that might have been destroyed
                pass
        
        # Copy to clipboard exactly as displayed
        self.root.clipboard_clear()
        self.root.clipboard_append(displayed_text)
        self.root.update()  # Force clipboard update
        
        print(f"Copied to clipboard: {displayed_text}")
    
    def copy_message_to_clipboard(self, message_data: Dict):
        """Copy the full message text to clipboard (legacy method)"""
        # Build the complete message text
        timestamp_str = message_data['timestamp'].strftime("%Y-%m-%d %H:%M:%S")
        
        if message_data['sender_name']:
            prefix = f"[{timestamp_str}] <{message_data['sender_name']}> "
        else:
            prefix = f"[{timestamp_str}] "
        
        # Extract message content
        if message_data['message_json']:
            message_content = self.extract_text_from_json(message_data['message_json'])
        else:
            message_content = message_data['message_text']
        
        full_message = prefix + message_content
        
        # Copy to clipboard
        self.root.clipboard_clear()
        self.root.clipboard_append(full_message)
        self.root.update()  # Force clipboard update
        
        print(f"Copied to clipboard: {full_message}")
    
    def run(self):
        """Start the GUI"""
        self.root.mainloop()

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python3 log_gui.py <database_path>")
        sys.exit(1)
    
    app = LogGUI(sys.argv[1])
    app.run()