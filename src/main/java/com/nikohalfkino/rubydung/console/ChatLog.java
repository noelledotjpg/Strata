package com.nikohalfkino.rubydung.console;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** chat backlog (irc-style lines) plus sent-line history, lives on RubyDung so it survives closing the chat screen */
public class ChatLog {

    public enum Kind {
        // "[12:34] <player> hello"
        MESSAGE,
        // "[12:34] *** Now talking in #strata", server notices and command output
        NOTICE
    }

    public static final class Line {
        public final String time;
        public final Kind kind;
        public final String nick;
        /** plain text, the copyable and link parts are in spans */
        public final String text;
        public final List<ChatText.Span> spans;
        /** pictures to show under the line */
        public final List<String> images;

        Line(String time, Kind kind, String nick, ChatText.Parsed parsed) {
            this.time = time;
            this.kind = kind;
            this.nick = nick;
            this.text = parsed.text;
            this.spans = parsed.spans;
            this.images = parsed.images;
        }
    }

    public static final int MAX_LINES = 500;
    public static final int MAX_HISTORY = 50;
    public static final String DEFAULT_NICK = "player";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Line> lines = new ArrayList<>();
    private final List<String> history = new ArrayList<>();

    private String nick = DEFAULT_NICK;
    private String channel = "#strata";

    /** channel is named after the world, like joining an irc channel */
    public void reset(String worldName) {
        this.lines.clear();

        String name = worldName == null ? "" : worldName.trim().toLowerCase().replaceAll("\\s+", "_");
        this.channel = "#" + (name.isEmpty() ? "strata" : name);

        notice("Now talking in " + this.channel);
        notice("Type .help for a list of commands.");
    }

    public void say(String text) {
        add(Kind.MESSAGE, this.nick, text);
    }

    public void notice(String text) {
        add(Kind.NOTICE, null, text);
    }

    private void add(Kind kind, String nick, String text) {
        // only system messages can mark copyable sections, what the player types is just checked for links
        ChatText.Parsed parsed = ChatText.parse(text == null ? "" : text, kind == Kind.NOTICE);
        this.lines.add(new Line(LocalTime.now().format(TIME_FORMAT), kind, nick, parsed));
        if (this.lines.size() > MAX_LINES) {
            this.lines.remove(0);
        }
    }

    public void clear() {
        this.lines.clear();
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(this.lines);
    }

    public String getNick() {
        return this.nick;
    }

    public void setNick(String nick) {
        if (nick != null && !nick.trim().isEmpty()) {
            this.nick = nick.trim();
        }
    }

    public String getChannel() {
        return this.channel;
    }

    /** sent lines, oldest first (for up/down recall) */
    public List<String> getHistory() {
        return this.history;
    }

    public void remember(String sent) {
        this.history.add(sent);
        if (this.history.size() > MAX_HISTORY) {
            this.history.remove(0);
        }
    }
}