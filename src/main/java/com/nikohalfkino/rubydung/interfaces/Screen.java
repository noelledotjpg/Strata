package com.nikohalfkino.rubydung.interfaces;

public abstract class Screen {

    protected int width;
    protected int height;

    public void init(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean pausesGame() {
        return true;
    }

    public abstract void render(int mouseX, int mouseY);

    public boolean keyPressed(int key, char character) {
        return false;
    }

    public void mouseClicked(int mouseX, int mouseY) {
    }

    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            mouseClicked(mouseX, mouseY);
        }
    }

    public void onClose() {
    }
}