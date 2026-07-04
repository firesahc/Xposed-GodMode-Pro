package com.kaisar.xposed.godmode.editor.overlay;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;

import java.util.Random;

public final class Particle {
    public static final int PART_WH = 8;
    public float cx;
    public float cy;
    public float radius;
    public int color;
    public float alpha;
    private static final Random random = new Random();
    public Rect mBound;

    public static Particle generateParticle(int color, Rect bound, Point point) {
        int row = point.y;
        int column = point.x;
        Particle particle = new Particle();
        particle.mBound = bound;
        particle.color = color;
        particle.alpha = 1f;
        particle.radius = PART_WH;
        particle.cx = bound.left + PART_WH * column;
        particle.cy = bound.top + PART_WH * row;
        return particle;
    }

    public void update(float factor) {
        cx = cx + factor * random.nextInt(mBound.width()) * (random.nextFloat() - 0.5f);
        cy = cy + factor * (mBound.height() / (random.nextInt(4) + 1));
        radius = radius - factor * random.nextInt(3);
        if (radius <= 0) radius = 0;
        alpha = 1f - factor;
    }

    public static Particle[][] generateParticles(Bitmap bitmap, Rect bound) {
        int w = bound.width();
        int h = bound.height();
        int partW_Count = w / Particle.PART_WH;
        int partH_Count = h / Particle.PART_WH;
        Particle[][] particles = new Particle[partH_Count][partW_Count];
        Point point;
        for (int row = 0; row < partH_Count; row++) {
            for (int column = 0; column < partW_Count; column++) {
                int color = bitmap.getPixel(column * Particle.PART_WH, row * Particle.PART_WH);
                point = new Point(column, row);
                particles[row][column] = Particle.generateParticle(color, bound, point);
            }
        }
        return particles;
    }
}
