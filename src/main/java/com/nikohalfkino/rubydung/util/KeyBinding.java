package com.nikohalfkino.rubydung.util;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * One rebindable action -> input mapping, shown as a row in {@link
 * com.nikohalfkino.rubydung.interfaces.screen.ControlsScreen}. Can be bound to either a
 * keyboard key or a mouse button (see {@link Device}) - needed for actions like breaking/placing
 * blocks, which are mouse buttons by default. Can also carry an optional modifier on top of the
 * primary binding (e.g. "Ctrl + E") - see {@link #hasModifier()}.
 * <p>
 * Grouped below by what they do (movement, flight, interaction, interface) rather than by
 * confirmed/placeholder status - every entry here is read somewhere: movement/jump/sneak/sprint
 * by {@code Player#tick}, break/place by {@code PlayerInteraction#tick}, and the rest directly by
 * {@code InputHandler}. This intentionally doesn't know anything about {@code InputHandler}
 * itself - it's just the data side of key rebinding.
 */
public class KeyBinding {

    public enum Device {
        KEYBOARD, MOUSE
    }

    private static final Map<String, KeyBinding> REGISTRY = new LinkedHashMap<>();
    private static final String[] MOUSE_BUTTON_NAMES = {"Left Click", "Right Click", "Middle Click"};
    private static final String KEYBINDS_FILE_NAME = "keybinds.txt";

    // --- Movement - read via isDown() in Player#tick, so binding one to a mouse button (odd,
    // but not wrong) actually works, unlike the interface group further down. ---
    public static final KeyBinding FORWARD = new KeyBinding("forward", "Move Forward", Keyboard.KEY_W, true);
    public static final KeyBinding BACK = new KeyBinding("back", "Move Backward", Keyboard.KEY_S, true);
    public static final KeyBinding LEFT = new KeyBinding("left", "Strafe Left", Keyboard.KEY_A, true);
    public static final KeyBinding RIGHT = new KeyBinding("right", "Strafe Right", Keyboard.KEY_D, true);
    public static final KeyBinding JUMP = new KeyBinding("jump", "Jump / Fly Up", Keyboard.KEY_SPACE, true);
    public static final KeyBinding SNEAK = new KeyBinding("sneak", "Fly Down", Keyboard.KEY_LSHIFT, true);

    // --- Flight. FLY_TOGGLE/NOCLIP_TOGGLE are mouseBindable = false because InputHandler only
    // ever compares them against a keyboard key event - a mouse button would silently do
    // nothing. FLY_SPEED_MODIFIER is read via isDown() (device-aware) but kept keyboard-only to
    // match how it's always behaved, since it's meant to be held alongside a scroll gesture. ---
    public static final KeyBinding FLY_TOGGLE = new KeyBinding("fly_toggle", "Toggle Flying", Keyboard.KEY_V, false);
    public static final KeyBinding NOCLIP_TOGGLE = new KeyBinding("noclip_toggle", "Toggle Noclip", Keyboard.KEY_N, false);
    public static final KeyBinding FLY_SPEED_MODIFIER = new KeyBinding("fly_speed_modifier", "Fly Speed Modifier", Keyboard.KEY_LCONTROL, false);

    // --- Interaction. BREAK_BLOCK/PLACE_BLOCK are read via isDown() in PlayerInteraction#tick,
    // so mouse rebinding works today; PICK_BLOCK/CYCLE_TREE are compared via getKey() (keyboard
    // only) in InputHandler. ---
    public static final KeyBinding BREAK_BLOCK = new KeyBinding("break_block", "Break Block", Device.MOUSE, 0, true);
    public static final KeyBinding PLACE_BLOCK = new KeyBinding("place_block", "Place Block", Device.MOUSE, 1, true);
    public static final KeyBinding PICK_BLOCK = new KeyBinding("pick_block", "Pick Block", Keyboard.KEY_LMENU, false);
    public static final KeyBinding CYCLE_TREE = new KeyBinding("cycle_tree", "Cycle Tile Type", Keyboard.KEY_TAB, false);

    // --- Interface. All three compared via getKey() (keyboard only) in InputHandler. ---
    public static final KeyBinding CONSOLE = new KeyBinding("console", "Open Console", Keyboard.KEY_GRAVE, false);
    public static final KeyBinding DEBUG_HUD = new KeyBinding("debug_hud", "Toggle Debug Info", Keyboard.KEY_F3, false);
    public static final KeyBinding SCREENSHOT = new KeyBinding("screenshot", "Screenshot", Keyboard.KEY_F2, false);

    private final String id;
    private final String displayName;
    private final Device defaultDevice;
    private final int defaultCode;

    /**
     * Whether this action can be rebound to a mouse button, not just a keyboard key. See the
     * per-group comments above for why each group is set the way it is.
     */
    private final boolean mouseBindable;

    private Device device;
    private int code;

    /**
     * Optional modifier held alongside the primary binding (e.g. Ctrl in "Ctrl + E"). Null means
     * no modifier - the common case. Set via the combo overload of {@link #setBinding} - see
     * {@code ControlsScreen}'s chord-capture logic for how a rebind turns into one of these.
     */
    private Device modifierDevice = null;
    private int modifierCode;

    private KeyBinding(String id, String displayName, int defaultKey, boolean mouseBindable) {
        this(id, displayName, Device.KEYBOARD, defaultKey, mouseBindable);
    }

    private KeyBinding(String id, String displayName, Device defaultDevice, int defaultCode, boolean mouseBindable) {
        this.id = id;
        this.displayName = displayName;
        this.defaultDevice = defaultDevice;
        this.defaultCode = defaultCode;
        this.mouseBindable = mouseBindable;
        this.device = defaultDevice;
        this.code = defaultCode;
        REGISTRY.put(id, this);
    }

    public static Collection<KeyBinding> all() {
        return REGISTRY.values();
    }

    public static KeyBinding byId(String id) {
        return REGISTRY.get(id);
    }

    /**
     * Read every binding's device/code (and modifier, if any) from {@value #KEYBINDS_FILE_NAME},
     * if it exists. Called once from {@code RubyDung#init}, before any screen (in particular
     * {@code ControlsScreen}, which reads {@link #getKeyName()} at construction time) could
     * possibly have been opened yet.
     * <p>
     * Only entries present in the file are touched - a binding with no matching line (new since
     * the file was written, or the file predates rebinding) is simply left at whatever it already
     * had, i.e. its hardcoded default. Same for one whose line fails to parse: {@link
     * #applySerialized} throws, this catches it per-binding rather than letting one bad line take
     * every other binding down with it, and that binding is left untouched too.
     */
    public static void load() {
        File file = new File(KEYBINDS_FILE_NAME);
        if (!file.isFile()) {
            return;
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        for (KeyBinding binding : REGISTRY.values()) {
            String raw = props.getProperty(binding.id);
            if (raw == null) {
                continue;
            }
            try {
                binding.applySerialized(raw);
            } catch (RuntimeException e) {
                // Malformed line (hand-edited file, or a stale entry from a build with a
                // different set of Device values) - leave this one binding at its default
                // rather than failing the whole load.
            }
        }
    }

    /**
     * Write every binding's current device/code (and modifier, if any) to {@value
     * #KEYBINDS_FILE_NAME}. Called once from {@code RubyDung#destroy}, alongside {@code
     * GameSettings#save()} - see that method's javadoc for why this is a separate file rather
     * than sharing one with the general options.
     */
    public static void save() {
        Properties props = new Properties();
        for (KeyBinding binding : REGISTRY.values()) {
            props.setProperty(binding.id, binding.serialize());
        }

        try (FileOutputStream out = new FileOutputStream(KEYBINDS_FILE_NAME)) {
            props.store(out, "Strata key bindings - see KeyBinding");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public boolean isMouseBindable() {
        return this.mouseBindable;
    }

    public Device getDevice() {
        return this.device;
    }

    public int getCode() {
        return this.code;
    }

    public boolean hasModifier() {
        return this.modifierDevice != null;
    }

    public Device getModifierDevice() {
        return this.modifierDevice;
    }

    public int getModifierCode() {
        return this.modifierCode;
    }

    /**
     * Keyboard-code accessor kept from before mouse bindings existed, for call sites (like
     * {@code InputHandler}) that only ever compare against a keyboard {@code key} event. Returns
     * whatever's bound even if it's actually a mouse button - those call sites only exist on
     * actions with {@link #isMouseBindable()} false, so that shouldn't happen in practice.
     */
    public int getKey() {
        return this.code;
    }

    /** Rebind to a plain primary binding with no modifier - clears any modifier that was set. */
    public void setBinding(Device device, int code) {
        this.device = device;
        this.code = code;
        this.modifierDevice = null;
    }

    /** Rebind to a combo - primary binding plus a modifier that must also be held. */
    public void setBinding(Device modifierDevice, int modifierCode, Device device, int code) {
        this.modifierDevice = modifierDevice;
        this.modifierCode = modifierCode;
        this.device = device;
        this.code = code;
    }

    /** Keyboard-only convenience for call sites that only ever bind to a key, never a mouse button. */
    public void setKey(int key) {
        setBinding(Device.KEYBOARD, key);
    }

    public void resetToDefault() {
        this.device = this.defaultDevice;
        this.code = this.defaultCode;
        this.modifierDevice = null;
    }

    /**
     * This binding as a single line for {@link #save()}: {@code "DEVICE:code"}, or {@code
     * "modDEVICE:modCode>DEVICE:code"} when {@link #hasModifier()}. Deliberately doesn't reuse
     * {@link #getBindingName()} - that's a display string ("Ctrl + E") meant for a human to read
     * on screen, not to round-trip through {@link #applySerialized}.
     */
    private String serialize() {
        String primary = this.device + ":" + this.code;
        if (this.modifierDevice == null) {
            return primary;
        }
        return this.modifierDevice + ":" + this.modifierCode + ">" + primary;
    }

    /**
     * Reverse of {@link #serialize()}. Throws on anything that doesn't parse cleanly - including
     * a primary binding to {@code Device.MOUSE} on a binding that isn't {@link #isMouseBindable()} -
     * so {@link #load()} can catch it and skip just this one line. Only mutates this binding once
     * the whole line is known to be valid, so a bad line can never leave a binding half-updated.
     */
    private void applySerialized(String raw) {
        String[] halves = raw.split(">", 2);
        String primaryToken = halves[halves.length - 1];
        String[] primaryParts = primaryToken.split(":", 2);
        Device device = Device.valueOf(primaryParts[0]);
        int code = Integer.parseInt(primaryParts[1]);

        if (device == Device.MOUSE && !this.mouseBindable) {
            throw new IllegalArgumentException("Not mouse-bindable: " + this.id);
        }

        if (halves.length == 1) {
            setBinding(device, code);
            return;
        }

        String[] modifierParts = halves[0].split(":", 2);
        Device modifierDevice = Device.valueOf(modifierParts[0]);
        int modifierCode = Integer.parseInt(modifierParts[1]);
        setBinding(modifierDevice, modifierCode, device, code);
    }

    public boolean isDown() {
        boolean primaryDown = this.device == Device.KEYBOARD ? Keyboard.isKeyDown(this.code) : Mouse.isButtonDown(this.code);
        if (!primaryDown) {
            return false;
        }
        if (this.modifierDevice == null) {
            return true;
        }
        return this.modifierDevice == Device.KEYBOARD ? Keyboard.isKeyDown(this.modifierCode) : Mouse.isButtonDown(this.modifierCode);
    }

    /** Human-readable name for whatever's currently bound, e.g. "W", "Right Click", or "Ctrl + E". */
    public String getBindingName() {
        String primary = describeBinding(this.device, this.code);
        if (this.modifierDevice == null) {
            return primary;
        }
        return describeBinding(this.modifierDevice, this.modifierCode) + " + " + primary;
    }

    /** @deprecated use {@link #getBindingName()} - kept as an alias now that bindings cover mouse buttons too. */
    @Deprecated
    public String getKeyName() {
        return getBindingName();
    }

    /**
     * Human-readable name for a single device+code pair, with friendly names for the common
     * modifier keys (raw {@link Keyboard#getKeyName} for e.g. left Ctrl is "LCONTROL", not
     * great for a combo label like "LCONTROL + E"). Shared between {@link #getBindingName()} and
     * {@code ControlsScreen}'s "listening for the second half of a combo" prompt.
     */
    public static String describeBinding(Device device, int code) {
        if (device == Device.MOUSE) {
            return code < MOUSE_BUTTON_NAMES.length ? MOUSE_BUTTON_NAMES[code] : "Mouse " + (code + 1);
        }
        switch (code) {
            case Keyboard.KEY_LCONTROL:
            case Keyboard.KEY_RCONTROL:
                return "Ctrl";
            case Keyboard.KEY_LSHIFT:
            case Keyboard.KEY_RSHIFT:
                return "Shift";
            case Keyboard.KEY_LMENU:
            case Keyboard.KEY_RMENU:
                return "Alt";
            case Keyboard.KEY_LMETA:
            case Keyboard.KEY_RMETA:
                return "Win";
            default:
                return Keyboard.getKeyName(code);
        }
    }

    /**
     * True if some other binding's primary is already this exact device+code, regardless of
     * modifier. {@code ControlsScreen} can surface this as a warning but still allow the rebind -
     * some players want deliberate overlaps.
     */
    public boolean conflictsWith(Device device, int code) {
        for (KeyBinding binding : REGISTRY.values()) {
            if (binding != this && binding.device == device && binding.code == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if some other binding is bound to this exact combo - same modifier (or lack of one)
     * and same primary. Stricter than {@link #conflictsWith(Device, int)}: e.g. plain "E" and
     * "Ctrl + E" don't conflict under this check, since they're distinguishable combos.
     */
    public boolean conflictsWith(Device modifierDevice, int modifierCode, Device device, int code) {
        for (KeyBinding binding : REGISTRY.values()) {
            if (binding == this) {
                continue;
            }
            boolean sameModifier = binding.modifierDevice == modifierDevice
                    && (modifierDevice == null || binding.modifierCode == modifierCode);
            if (sameModifier && binding.device == device && binding.code == code) {
                return true;
            }
        }
        return false;
    }
}