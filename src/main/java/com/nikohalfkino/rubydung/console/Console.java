package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.commands.*;
import com.nikohalfkino.rubydung.interfaces.widget.TextField;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;

public class Console {

    public static final int MAX_LOG_LINES = 200;

    private final RubyDung game;
    private final Map<String, Command> commands = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private final List<String> commandHistory = new ArrayList<>();

    private final TextField input = new TextField();
    private int historyIndex = -1;
    private boolean open = false;

    public Console(RubyDung game) {
        this.game = game;

        register(new HelpCommand());
        register(new ClearCommand());
        register(new NoClipCommand());
        register(new TeleportCommand());
        register(new FlyCommand());
        register(new ScreenshotCommand());
        register(new SeedCommand());
        register(new RegenerateCommand());

        println("Type 'help' for a list of commands.");
    }

    public void register(Command command) {
        this.commands.put(command.name().toLowerCase(), command);
    }

    public List<Command> commands() {
        List<Command> list = new ArrayList<>(this.commands.values());
        list.sort(Comparator.comparing(Command::name));
        return list;
    }

    public boolean isOpen() {
        return this.open;
    }

    public void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }

        this.open = open;
        Keyboard.enableRepeatEvents(open);

        if (open) {
            this.input.clear();
            this.historyIndex = -1;
            Mouse.setGrabbed(false);
        } else {
            Mouse.setGrabbed(this.game.getCurrentScreen() == null);
        }
    }

    public void toggle() {
        setOpen(!this.open);
    }

    public void println(String line) {
        this.log.add(line);
        if (this.log.size() > MAX_LOG_LINES) {
            this.log.remove(0);
        }
    }

    public void clear() {
        this.log.clear();
    }

    public List<String> getLog() {
        return this.log;
    }

    public TextField getInput() {
        return this.input;
    }

    public void keyTyped(int key, char character) {
        if (key == KeyMap.ESC) {
            setOpen(false);
            return;
        }

        if (key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER) {
            submit();
            return;
        }

        if (key == KeyMap.UP) {
            navigateHistory(-1);
            return;
        }

        if (key == KeyMap.DOWN) {
            navigateHistory(1);
            return;
        }

        this.input.keyTyped(key, character);
    }

    private void navigateHistory(int direction) {
        if (this.commandHistory.isEmpty()) {
            return;
        }

        if (direction < 0) {
            if (this.historyIndex < this.commandHistory.size() - 1) {
                this.historyIndex++;
            }
        } else if (direction > 0) {
            if (this.historyIndex >= 0) {
                this.historyIndex--;
            }
        }

        if (this.historyIndex >= 0) {
            this.input.setText(this.commandHistory.get(this.commandHistory.size() - 1 - this.historyIndex));
        } else {
            this.input.clear();
        }
    }

    private void submit() {
        String line = this.input.getText().trim();
        this.input.clear();
        this.historyIndex = -1;

        if (line.isEmpty()) {
            return;
        }

        this.commandHistory.add(line);
        println("] " + line);

        String[] parts = line.split("\\s+");
        String name = parts[0].toLowerCase();
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        Command command = this.commands.get(name);
        if (command == null) {
            println("Unknown command: " + name + " (try 'help')");
            return;
        }

        try {
            command.execute(this.game, this, args);
        } catch (Exception e) {
            println("Error running '" + name + "': " + e.getMessage());
        }
    }
}