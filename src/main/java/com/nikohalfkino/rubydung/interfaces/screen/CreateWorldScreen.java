package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.ChoiceRow;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.interfaces.widget.Slider;
import com.nikohalfkino.rubydung.interfaces.widget.TextInput;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

/**
 * new-world form laid out like Options: a panel with a dim header per section (World, Terrain, Size, Time), Create / Cancel at the bottom
 * the size is a slider over the WorldSize presets
 *
 * the seed box only shows for natural, island and archipelago terrain and the time-of-day row only while the clock is frozen, gaps are adjusted so sections stay evenly spaced
 */
public class CreateWorldScreen extends MenuScreen {

    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_SEED_LENGTH = 32;

    // a bit wider than the other menus, so the four terrain buttons and the size slider have room
    private static final float PANEL_WIDTH   = UiStyle.PANEL_WIDTH + 80.0f;
    private static final float PANEL_PADDING = 20.0f;
    private static final float PANEL_TOP     = 90.0f;

    private static final float FIELD_HEIGHT  = UiStyle.ROW_HEIGHT;
    private static final float BUTTON_HEIGHT = UiStyle.BUTTON_HEIGHT;

    private static final float ITEM_GAP    = 8.0f;
    private static final float HEADER_GAP  = 6.0f;
    private static final float SECTION_GAP = 16.0f;

    private static final float TYPE_GAP   = 12.0f;
    private static final float CHOICE_GAP = 12.0f;

    // Start times offered when the clock is frozen (0 = midnight, 0.25 = dawn, 0.5 = noon, 0.75 = dusk).
    private static final float TIME_MIDNIGHT = 0.0f;
    private static final float TIME_DAWN = 0.25f;
    private static final float TIME_NOON = 0.5f;
    private static final float TIME_DUSK = 0.75f;

    private static final LevelGenerator.WorldSize[] SIZES = LevelGenerator.WorldSize.values();

    // from this size on, a red warning shows under the slider
    private static final LevelGenerator.WorldSize LAG_WARNING_FROM = LevelGenerator.WorldSize.XXL;

    private final TextInput nameInput;
    private final TextInput seedInput;
    // the four terrain presets are split over two rows of two, they share one selection
    private final ChoiceRow<LevelGenerator.WorldType> typeRowTop;
    private final ChoiceRow<LevelGenerator.WorldType> typeRowBottom;
    private final Slider sizeSlider;
    private final ChoiceRow<DayNightCycle.TimeMode> timeModeRow;

    private LevelGenerator.WorldType worldType = LevelGenerator.WorldType.FLAT;
    private LevelGenerator.WorldSize worldSize = LevelGenerator.WorldSize.DEFAULT;
    private DayNightCycle.TimeMode timeMode = DayNightCycle.TimeMode.NORMAL;
    private float frozenStartTime = TIME_NOON;
    private String error = null;

    public CreateWorldScreen(RubyDung game) {
        super(game);

        setKeyRepeat(true);
        setSwallowKeys(true);
        // The name / seed box keeps the keyboard when other buttons are clicked.
        setStickyFocus(true);
        onEscape(this::cancel);
        onEnter(this::tryCreate);

        addTitle("New World");

        Panel panel = add(new Panel(ITEM_GAP), Anchor.TOP_CENTER, 0, PANEL_TOP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadowAlpha(1.0f);
        panel.setCrossAlign(0.0f);

        // world
        panel.add(new Label("World").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        this.nameInput = panel.add(
                new TextInput("Name: ", PANEL_WIDTH, FIELD_HEIGHT, MAX_NAME_LENGTH), SECTION_GAP);

        // terrain
        panel.add(new Label("Terrain").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        this.typeRowTop = panel.add(
                new ChoiceRow<LevelGenerator.WorldType>(PANEL_WIDTH, BUTTON_HEIGHT, TYPE_GAP)
                        .option("Flat", LevelGenerator.WorldType.FLAT)
                        .option("Natural", LevelGenerator.WorldType.NATURAL)
                        .onChange(this::selectType));
        this.typeRowBottom = panel.add(
                new ChoiceRow<LevelGenerator.WorldType>(PANEL_WIDTH, BUTTON_HEIGHT, TYPE_GAP)
                        .option("Island", LevelGenerator.WorldType.ISLAND)
                        .option("Archipelago", LevelGenerator.WorldType.ARCHIPELAGO)
                        .onChange(this::selectType));
        this.typeRowTop.select(this.worldType);
        this.typeRowBottom.select(this.worldType);
        updateTypeGap();

        this.seedInput = panel.add(
                new TextInput("Seed: ", PANEL_WIDTH, FIELD_HEIGHT, MAX_SEED_LENGTH), SECTION_GAP);
        this.seedInput.setVisibleWhen(this::showSeedField);

        // size
        panel.add(new Label("Size").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        // one stop per preset (Tiny .. XXL), a click or drag on the track picks it
        this.sizeSlider = new Slider("Size", PANEL_WIDTH, FIELD_HEIGHT,
                0, SIZES.length - 1, this.worldSize.ordinal(), true)
                .setFormatter(v -> sizeText(SIZES[Math.round(v)]))
                .onChange(v -> selectSize(SIZES[Math.round(v)]));
        panel.add(this.sizeSlider);
        updateSizeGap();

        // red and centred under the slider, takes no space unless the size is big enough
        Label lagWarning = panel.add(new Label("(This might lag a lot!)").setColor(UiStyle.TEXT_ERROR), SECTION_GAP);
        lagWarning.setSize(PANEL_WIDTH, -1.0f);
        lagWarning.setVisibleWhen(this::showLagWarning);

        // time: Real-Time / Normal / Frozen, plus a time-of-day row when frozen
        panel.add(new Label("Time").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        this.timeModeRow = panel.add(
                new ChoiceRow<DayNightCycle.TimeMode>(PANEL_WIDTH, BUTTON_HEIGHT, CHOICE_GAP)
                        .option("Real-Time", DayNightCycle.TimeMode.REAL_TIME)
                        .option("Normal", DayNightCycle.TimeMode.NORMAL)
                        .option("Frozen", DayNightCycle.TimeMode.FROZEN)
                        .onChange(this::selectTimeMode));
        this.timeModeRow.select(this.timeMode);
        updateTimeModeGap();

        ChoiceRow<Float> timeOfDayRow = panel.add(
                new ChoiceRow<Float>(PANEL_WIDTH, BUTTON_HEIGHT, CHOICE_GAP)
                        .option("Midnight", TIME_MIDNIGHT)
                        .option("Dawn", TIME_DAWN)
                        .option("Noon", TIME_NOON)
                        .option("Dusk", TIME_DUSK)
                        .onChange(time -> this.frozenStartTime = time),
                SECTION_GAP);
        timeOfDayRow.select(this.frozenStartTime);
        timeOfDayRow.setVisibleWhen(this::showTimeOfDayRow);

        // create | Cancel
        float buttonWidth = (PANEL_WIDTH - UiStyle.BUTTON_GAP) / 2.0f;
        Row bottom = panel.add(new Row(UiStyle.BUTTON_GAP), ITEM_GAP);
        bottom.add(new Button("Create", buttonWidth, BUTTON_HEIGHT, true).onClick(this::tryCreate));
        bottom.add(new Button("Cancel", buttonWidth, BUTTON_HEIGHT, true).onClick(this::cancel));

        // Error line, centred over the panel's content; takes no space until there is an error.
        Label errorLabel = panel.add(new Label(() -> this.error)
                .setColor(UiStyle.TEXT_ERROR)
                .setMaxWidth((int) PANEL_WIDTH));
        errorLabel.setSize(PANEL_WIDTH, -1.0f);
        errorLabel.setVisibleWhen(() -> this.error != null);

        setFocus(this.nameInput);
    }

    private static String sizeText(LevelGenerator.WorldSize size) {
        String name;
        switch (size) {
            case TINY:
                name = "Tiny";
                break;
            case DEFAULT:
                name = "Default";
                break;
            case LARGE:
                name = "Large";
                break;
            case INSANE:
                name = "XL";
                break;
            default:
                name = "XXL";
                break;
        }
        return name + " (" + size.width + " x " + size.height + ")";
    }

    private boolean showLagWarning() {
        return this.worldSize.ordinal() >= LAG_WARNING_FROM.ordinal();
    }

    private void selectSize(LevelGenerator.WorldSize size) {
        this.worldSize = size;
        updateSizeGap();
    }

    // the slider sits close above the warning, without it the next section follows at the section gap
    private void updateSizeGap() {
        this.sizeSlider.setGapAfter(showLagWarning() ? ITEM_GAP : SECTION_GAP);
    }

    private boolean showTimeOfDayRow() {
        return this.timeMode == DayNightCycle.TimeMode.FROZEN;
    }

    private boolean showSeedField() {
        return this.worldType != LevelGenerator.WorldType.FLAT;
    }

    private void selectType(LevelGenerator.WorldType type) {
        this.worldType = type;
        // only the row that holds the type lights up
        this.typeRowTop.select(type);
        this.typeRowBottom.select(type);
        updateTypeGap();

        if (!showSeedField() && getFocus() == this.seedInput) {
            setFocus(this.nameInput);
        }
    }

    private void selectTimeMode(DayNightCycle.TimeMode mode) {
        this.timeMode = mode;
        updateTimeModeGap();
    }

    // seed box sits close under the type row, without it the next section follows at the section gap
    private void updateTypeGap() {
        this.typeRowBottom.setGapAfter(showSeedField() ? ITEM_GAP : SECTION_GAP);
    }

    // same for the time-of-day row under the mode row
    private void updateTimeModeGap() {
        this.timeModeRow.setGapAfter(showTimeOfDayRow() ? ITEM_GAP : SECTION_GAP);
    }

    private void cancel() {
        this.game.openScreen(new WorldListScreen(this.game));
    }

    private void tryCreate() {
        String name = WorldManager.sanitize(this.nameInput.getText());

        if (name.isEmpty()) {
            this.error = "Enter a world name";
            return;
        }
        if (WorldManager.exists(name)) {
            this.error = "A world named \"" + name + "\" already exists";
            return;
        }

        float startTime = this.timeMode == DayNightCycle.TimeMode.FROZEN
                ? this.frozenStartTime : DayNightCycle.DEFAULT_START_TIME;

        String seedText = showSeedField() ? this.seedInput.getText().trim() : "";
        if (seedText.isEmpty()) {
            this.game.startNewWorld(name, this.worldType, this.worldSize, this.timeMode, startTime);
        } else {
            this.game.startNewWorld(name, this.worldType, LevelGenerator.parseSeed(seedText), this.worldSize,
                    this.timeMode, startTime);
        }
    }
}