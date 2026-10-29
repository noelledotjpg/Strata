package com.nikohalfkino.rubydung.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class GameSettings {

    private static final String OPTIONS_FILE_NAME = "options.txt";

    public static final float MIN_FOV = 30.0f;
    public static final float MAX_FOV = 110.0f;
    public static final float DEFAULT_FOV = 70.0f;

    public static final int UNCAPPED_FPS = 0;
    private static final int[] FPS_CAP_STEPS = {30, 60, 120, 144, 240, UNCAPPED_FPS};

    private static float fov = DEFAULT_FOV;
    private static boolean vsyncEnabled = true;
    private static int fpsCap = 60;
    private static boolean showFpsInHud = true;
    private static float musicVolume = 0.15f;

    private GameSettings() {
    }

    public static float getFov() {
        return fov;
    }

    public static void setFov(float value) {
        fov = Math.max(MIN_FOV, Math.min(MAX_FOV, value));
    }

    public static boolean isVsyncEnabled() {
        return vsyncEnabled;
    }

    public static void setVsyncEnabled(boolean enabled) {
        vsyncEnabled = enabled;
    }

    public static int getFpsCap() {
        return fpsCap;
    }

    public static void setFpsCap(int cap) {
        fpsCap = cap;
    }

    public static void cycleFpsCap() {
        int currentIndex = 0;
        for (int i = 0; i < FPS_CAP_STEPS.length; i++) {
            if (FPS_CAP_STEPS[i] == fpsCap) {
                currentIndex = i;
                break;
            }
        }
        fpsCap = FPS_CAP_STEPS[(currentIndex + 1) % FPS_CAP_STEPS.length];
    }

    public static float getMusicVolume() {
        return musicVolume;
    }

    public static void setMusicVolume(float volume) {
        musicVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public static boolean isShowFpsInHud() {
        return showFpsInHud;
    }

    public static void setShowFpsInHud(boolean show) {
        showFpsInHud = show;
    }

    public static void load() {
        File file = new File(OPTIONS_FILE_NAME);
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

        setFov(parseFloat(props.getProperty("fov"), fov));
        vsyncEnabled = parseBoolean(props.getProperty("vsyncEnabled"), vsyncEnabled);
        fpsCap = parseInt(props.getProperty("fpsCap"), fpsCap);
        showFpsInHud = parseBoolean(props.getProperty("showFpsInHud"), showFpsInHud);
        setMusicVolume(parseFloat(props.getProperty("musicVolume"), musicVolume));
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("fov", Float.toString(fov));
        props.setProperty("vsyncEnabled", Boolean.toString(vsyncEnabled));
        props.setProperty("fpsCap", Integer.toString(fpsCap));
        props.setProperty("showFpsInHud", Boolean.toString(showFpsInHud));
        props.setProperty("musicVolume", Float.toString(musicVolume));

        try (FileOutputStream out = new FileOutputStream(OPTIONS_FILE_NAME)) {
            props.store(out, "Strata video/general options - see GameSettings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }
}