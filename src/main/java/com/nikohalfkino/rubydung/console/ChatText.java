package com.nikohalfkino.rubydung.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * the clickable parts of a chat line
 *
 * a line is stored as plain text plus spans over it: links (http / https, found in the text or written as
 * "[label](url)") and copyable sections. a bare link to a png / jpg / gif / bmp is also listed in images so the
 * chat screen can show the picture under the line
 *
 * system messages mark a copyable section with copyable(), e.g. println("World seed: " + ChatText.copyable("123")).
 * the markers are private-use characters, so strip() gives the plain text for places that can't show spans
 * (the console). text typed by the player never gets copy sections, the markers are removed from it
 */
public final class ChatText {

    public static final char COPY_START = '\uE000';
    public static final char COPY_END = '\uE001';
    public static final char COPY_SEP = '\uE002';

    public enum Kind { LINK, COPY }

    public static final class Span {
        /** [start, end) in the plain text */
        public final int start;
        public final int end;
        public final Kind kind;
        /** the url to open, or the text to copy */
        public final String value;

        Span(int start, int end, Kind kind, String value) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.value = value;
        }
    }

    public static final class Parsed {
        public final String text;
        /** in order of position, never overlapping */
        public final List<Span> spans;
        /** urls of pictures to show under the line */
        public final List<String> images;

        Parsed(String text, List<Span> spans, List<String> images) {
            this.text = text;
            this.spans = Collections.unmodifiableList(spans);
            this.images = Collections.unmodifiableList(images);
        }
    }

    public static final int MAX_IMAGES_PER_LINE = 3;

    // "[label](url)" or a bare url
    private static final Pattern LINKS = Pattern.compile(
            "\\[([^\\]\\n]{1,200})\\]\\((https?://[^)\\s]{1,2000})\\)|(https?://[^\\s<>\"\\uE000-\\uE002]{1,2000})");

    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".gif", ".bmp"};

    private ChatText() {
    }

    /** marks value as a section that is copied when clicked, shown as the value itself */
    public static String copyable(String value) {
        return COPY_START + removeMarkers(value) + COPY_END;
    }

    /** marks a section that shows label and copies value when clicked */
    public static String copyable(String label, String value) {
        return COPY_START + removeMarkers(value) + COPY_SEP + removeMarkers(label) + COPY_END;
    }

    /** the text as it reads, without the copy markers */
    public static String strip(String raw) {
        return raw == null ? "" : parse(raw, true).text;
    }

    public static boolean isImageUrl(String url) {
        int cut = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }

        String path = url.substring(0, cut).toLowerCase();
        for (String extension : IMAGE_EXTENSIONS) {
            if (path.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** allowMarkers: whether copy markers in raw are honoured (system messages) or just dropped (player text) */
    public static Parsed parse(String raw, boolean allowMarkers) {
        StringBuilder out = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        List<String> images = new ArrayList<>();

        int plainStart = 0;
        int i = 0;

        while (i < raw.length()) {
            if (allowMarkers && raw.charAt(i) == COPY_START) {
                int end = raw.indexOf(COPY_END, i + 1);

                if (end > 0) {
                    appendPlain(out, spans, images, raw.substring(plainStart, i));

                    String body = raw.substring(i + 1, end);
                    int sep = body.indexOf(COPY_SEP);
                    String value = removeMarkers(sep < 0 ? body : body.substring(0, sep));
                    String label = removeMarkers(sep < 0 ? body : body.substring(sep + 1));

                    if (!label.isEmpty()) {
                        int start = out.length();
                        out.append(label);
                        spans.add(new Span(start, out.length(), Kind.COPY, value));
                    }

                    i = end + 1;
                    plainStart = i;
                    continue;
                }
            }
            i++;
        }

        appendPlain(out, spans, images, raw.substring(plainStart));

        return new Parsed(out.toString(), spans, images);
    }

    private static void appendPlain(StringBuilder out, List<Span> spans, List<String> images, String plain) {
        plain = removeMarkers(plain);

        Matcher m = LINKS.matcher(plain);
        int last = 0;

        while (m.find()) {
            out.append(plain, last, m.start());

            if (m.group(2) != null) {
                // [label](url): the label is the link, nothing is embedded
                int start = out.length();
                out.append(m.group(1));
                spans.add(new Span(start, out.length(), Kind.LINK, m.group(2)));
                last = m.end();
                continue;
            }

            String url = m.group(3);
            int length = trimmedLength(url);
            url = url.substring(0, length);

            int start = out.length();
            out.append(url);
            spans.add(new Span(start, out.length(), Kind.LINK, url));

            if (isImageUrl(url) && images.size() < MAX_IMAGES_PER_LINE && !images.contains(url)) {
                images.add(url);
            }

            // what was cut off (a full stop, a closing bracket) goes back to being plain text
            last = m.start(3) + length;
        }

        out.append(plain, last, plain.length());
    }

    // length of url without the punctuation that belongs to the sentence around it
    private static int trimmedLength(String url) {
        int end = url.length();

        while (end > 0) {
            char c = url.charAt(end - 1);

            if (".,;:!?'\"]}".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(url, 0, end, ')') > count(url, 0, end, '(')) {
                end--;
            } else {
                break;
            }
        }

        return end;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String removeMarkers(String s) {
        if (s.indexOf(COPY_START) < 0 && s.indexOf(COPY_END) < 0 && s.indexOf(COPY_SEP) < 0) {
            return s;
        }

        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != COPY_START && c != COPY_END && c != COPY_SEP) {
                clean.append(c);
            }
        }
        return clean.toString();
    }
}
