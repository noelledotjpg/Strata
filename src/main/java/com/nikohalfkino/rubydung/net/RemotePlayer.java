package com.nikohalfkino.rubydung.net;

/** another player as this machine sees them: the last position the server told us and a smoothed copy of it that is drawn */
public final class RemotePlayer {

    public final int id;
    public final String name;

    // last known state (eye position, like Player.x/y/z)
    public double x, y, z;
    public float yaw, pitch, bodyYaw;
    public int flags;

    // what is drawn: eases toward the last known state
    public double drawX, drawY, drawZ;
    public float drawBodyYaw;

    public RemotePlayer(int id, String name, double x, double y, double z) {
        this.id = id;
        this.name = name;
        setState(x, y, z, 0.0F, 0.0F, 0.0F, 0);
        this.drawX = x;
        this.drawY = y;
        this.drawZ = z;
        this.drawBodyYaw = 0.0F;
    }

    public void setState(double x, double y, double z, float yaw, float pitch, float bodyYaw, int flags) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.bodyYaw = bodyYaw;
        this.flags = flags;
    }

    /** eases the drawn state toward the real one, seconds is the time since the last frame */
    public void step(float seconds) {
        double alpha = 1.0D - Math.exp(-seconds * 18.0D);

        // too far away (teleported): jump
        double dx = this.x - this.drawX;
        double dy = this.y - this.drawY;
        double dz = this.z - this.drawZ;
        if (dx * dx + dy * dy + dz * dz > 32.0D * 32.0D) {
            alpha = 1.0D;
        }

        this.drawX += dx * alpha;
        this.drawY += dy * alpha;
        this.drawZ += dz * alpha;

        float diff = ((this.bodyYaw - this.drawBodyYaw) % 360.0F + 540.0F) % 360.0F - 180.0F;
        this.drawBodyYaw += diff * (float) alpha;
    }
}
