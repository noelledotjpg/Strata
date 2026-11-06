package com.nikohalfkino.rubydung.net;

/** one colour per player name, shared by chat, the user list and name tags so a player looks the same everywhere */
public final class PlayerColors {

    private static final int[] PALETTE = {
            0xFF7777, 0x77FF77, 0x7799FF, 0xFFFF66,
            0xFF77FF, 0x66FFFF, 0xFFAA55, 0xBB99FF
    };

    private PlayerColors() {
    }

    /** 0xRRGGBB, the same for the same name (case-insensitive) on every machine */
    public static int of(String name) {
        String key = name == null ? "" : name.toLowerCase();
        return PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)];
    }
}
