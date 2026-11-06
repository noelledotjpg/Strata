package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.audio.tts.JTalkSpeaker;
import com.nikohalfkino.rubydung.console.CommandSyntax;
import com.nikohalfkino.rubydung.interfaces.widget.TextField;

import java.util.Arrays;
import java.util.regex.Matcher;

/**
 * colours the chat input: ".commands" the way the console reads them (wrong arguments red),
 * everything else for singing notes, so bah<235,30> and [G4] show what the voice will parse
 */
public final class ChatInputSyntax implements TextField.Highlighter {

    private static final int DOT = CommandSyntax.COMMAND;

    private static final int PHONEME = 0xFFB86B;
    private static final int DURATION = 0xFFE066;
    private static final int PITCH = 0x66E0FF;
    private static final int REST = 0xBB99FF;
    private static final int PUNCTUATION = 0x8899AA;
    private static final int BAD = CommandSyntax.ERROR;

    private final CommandSyntax commands;

    public ChatInputSyntax(CommandSyntax commands) {
        this.commands = commands;
    }

    /** a line starting with "." is a command, except ".<200,00>" which is a pause in a song */
    public static boolean isCommand(String text) {
        return text.startsWith(".") && !text.startsWith(".<");
    }

    @Override
    public int[] colors(String text, int cursor) {
        if (isCommand(text)) {
            int[] out = new int[text.length()];
            Arrays.fill(out, CommandSyntax.NORMAL);
            out[0] = DOT;

            int[] rest = this.commands.highlight(text.substring(1), cursor - 1);
            System.arraycopy(rest, 0, out, 1, rest.length);

            return out;
        }

        return singing(text);
    }

    private static int[] singing(String text) {
        int[] out = new int[text.length()];
        Arrays.fill(out, CommandSyntax.NORMAL);

        boolean any = false;

        // phoneme<duration,pitch>
        Matcher note = JTalkSpeaker.SINGING_NOTE.matcher(text);
        while (note.find()) {
            any = true;

            paint(out, note.start(), note.end(), PUNCTUATION);
            paint(out, note.start(2), note.end(2), DURATION);

            if (JTalkSpeaker.isRest(note.group(1))) {
                // a pause, the pitch isn't used
                paint(out, note.start(1), note.end(1), REST);
                paint(out, note.start(3), note.end(3), PUNCTUATION);
                continue;
            }

            paint(out, note.start(1), note.end(1), PHONEME);
            paint(out, note.start(3), note.end(3),
                    JTalkSpeaker.isValidPitch(note.group(3)) ? PITCH : BAD);
        }

        // [+7] [G4], only where the voice treats it as a note (a bare [a] is just text)
        Matcher marker = JTalkSpeaker.PITCH_MARKER.matcher(text);
        while (marker.find()) {
            if (!JTalkSpeaker.isSingingMarker(marker.group(1))) {
                continue;
            }

            any = true;

            paint(out, marker.start(), marker.end(), PUNCTUATION);
            paint(out, marker.start(1), marker.end(1), PITCH);
        }

        // the [ ] around a phrase of notes
        if (any) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c == '[' || c == ']') && out[i] == CommandSyntax.NORMAL) {
                    out[i] = PUNCTUATION;
                }
            }
        }

        return out;
    }

    private static void paint(int[] out, int from, int to, int color) {
        for (int i = from; i < to; i++) {
            out[i] = color;
        }
    }
}
