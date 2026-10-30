package com.openuc2.control;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stage overview: planned tiles (outlines), captured tiles (thumbnails at
 * their commanded positions), the current field of view, a grid and a scale
 * bar. The view only zooms out, never in, until the next scan starts, so
 * moving the stage visibly moves the green field of view across the map.
 * A tap reports the stage position under the finger.
 *
 * Map axes are image axes: stage position (x, y) is drawn at (fx·x, fy·y)
 * with fx, fy = ±1. Flip an axis when the thumbnails do not line up, i.e.
 * when the camera sees the stage move the other way.
 */
public class StageMapView extends View {

    public interface OnTapListener {
        void onTap(double xUm, double yUm);
    }

    private final Paint planPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fovPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gridPaint = new Paint();
    private final int background;
    private final float density;

    private final List<double[]> planned = new ArrayList<>();
    private final List<double[]> shotPos = new ArrayList<>();
    private final List<Bitmap> shots = new ArrayList<>();
    private double posX, posY, fovW = 1000, fovH = 1000;
    private int fx = 1, fy = 1;
    private OnTapListener tapListener;

    // Centres seen since the last scan started, map µm. Only ever grows.
    private double bx0 = Double.MAX_VALUE, bx1 = -Double.MAX_VALUE;
    private double by0 = Double.MAX_VALUE, by1 = -Double.MAX_VALUE;

    // Transform of the last draw, map µm -> px, kept for taps.
    private double minX, minY, scale = 1;
    private float ox, oy, downX, downY;

    public StageMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        background = ContextCompat.getColor(context, R.color.surface_sunken);
        planPaint.setStyle(Paint.Style.STROKE);
        planPaint.setStrokeWidth(density);
        planPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        fovPaint.setStyle(Paint.Style.STROKE);
        fovPaint.setStrokeWidth(2.5f * density);
        fovPaint.setColor(ContextCompat.getColor(context, R.color.go));
        gridPaint.setColor(ContextCompat.getColor(context, R.color.divider));
        textPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        textPaint.setStrokeWidth(2 * density);
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
    }

    public void setOnTapListener(OnTapListener l) { tapListener = l; }

    public void setFov(double widthUm, double heightUm) {
        fovW = Math.max(widthUm, 1e-3);
        fovH = Math.max(heightUm, 1e-3);
        invalidate();
    }

    public void setPosition(double xUm, double yUm) {
        posX = xUm;
        posY = yUm;
        invalidate();
    }

    public void setFlip(boolean flipX, boolean flipY) {
        int nfx = flipX ? -1 : 1, nfy = flipY ? -1 : 1;
        if (nfx != fx || nfy != fy) resetBounds();   // bounds are in map coordinates
        fx = nfx;
        fy = nfy;
        invalidate();
    }

    public void setPlanned(List<TileScan.Tile> tiles) {
        planned.clear();
        for (TileScan.Tile t : tiles) planned.add(new double[]{t.x, t.y});
        invalidate();
    }

    public void addShot(double xUm, double yUm, Bitmap thumb) {
        shotPos.add(new double[]{xUm, yUm});
        shots.add(thumb);
        invalidate();
    }

    /** A new scan: drop the thumbnails and fit the view to the new plan. */
    public void clearShots() {
        shots.clear();   // no recycle(): a frame may still be drawing them
        shotPos.clear();
        resetBounds();
        invalidate();
    }

    private void resetBounds() {
        bx0 = by0 = Double.MAX_VALUE;
        bx1 = by1 = -Double.MAX_VALUE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        fitBounds();
        canvas.drawColor(background);
        drawGrid(canvas);
        for (int i = 0; i < shots.size(); i++) {
            canvas.drawBitmap(shots.get(i), null, rect(shotPos.get(i)), bitmapPaint);
        }
        for (double[] p : planned) canvas.drawRect(rect(p), planPaint);
        RectF here = rect(new double[]{posX, posY});
        canvas.drawRect(here, fovPaint);
        float cross = 6 * density;
        canvas.drawLine(here.centerX() - cross, here.centerY(), here.centerX() + cross, here.centerY(), fovPaint);
        canvas.drawLine(here.centerX(), here.centerY() - cross, here.centerX(), here.centerY() + cross, fovPaint);
        drawScaleBar(canvas);
    }

    /** Everything seen so far (positions, plan, shots) in view, uniform scale, centred. */
    private void fitBounds() {
        include(posX, posY);
        for (double[] p : planned) include(p[0], p[1]);
        for (double[] p : shotPos) include(p[0], p[1]);
        double pad = 0.08 * Math.max(bx1 - bx0 + fovW, by1 - by0 + fovH);
        double x0 = bx0 - fovW / 2 - pad, x1 = bx1 + fovW / 2 + pad;
        double y0 = by0 - fovH / 2 - pad, y1 = by1 + fovH / 2 + pad;
        scale = Math.min(getWidth() / (x1 - x0), getHeight() / (y1 - y0));
        ox = (float) ((getWidth() - (x1 - x0) * scale) / 2);
        oy = (float) ((getHeight() - (y1 - y0) * scale) / 2);
        minX = x0;
        minY = y0;
    }

    private void include(double x, double y) {
        bx0 = Math.min(bx0, fx * x);
        bx1 = Math.max(bx1, fx * x);
        by0 = Math.min(by0, fy * y);
        by1 = Math.max(by1, fy * y);
    }

    /** Lines every scale-bar length, fixed to the stage, so motion shows. */
    private void drawGrid(Canvas canvas) {
        double step = barLength();
        if (!(step > 0)) return;   // not laid out yet
        double right = minX + getWidth() / scale, bottom = minY + getHeight() / scale;
        for (double g = Math.ceil(minX / step) * step; g < right; g += step) {
            float x = (float) (ox + (g - minX) * scale);
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
        }
        for (double g = Math.ceil(minY / step) * step; g < bottom; g += step) {
            float y = (float) (oy + (g - minY) * scale);
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }
    }

    /** Screen rectangle of one field of view centred on stage position p. */
    private RectF rect(double[] p) {
        float cx = (float) (ox + (fx * p[0] - minX) * scale);
        float cy = (float) (oy + (fy * p[1] - minY) * scale);
        float hw = (float) (fovW * scale / 2), hh = (float) (fovH * scale / 2);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** 1-2-5 length of about a quarter of the view width, µm. */
    private double barLength() {
        double target = getWidth() / scale / 4;
        double len = Math.pow(10, Math.floor(Math.log10(target)));
        if (len * 5 <= target) len *= 5;
        else if (len * 2 <= target) len *= 2;
        return len;
    }

    private void drawScaleBar(Canvas canvas) {
        double len = barLength();
        float x = 10 * density, y = getHeight() - 10 * density;
        canvas.drawLine(x, y, x + (float) (len * scale), y, textPaint);
        String label = len >= 1000 ? fmt(len / 1000) + " mm" : fmt(len) + " µm";
        canvas.drawText(label, x, y - 5 * density, textPaint);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (tapListener == null || !isEnabled()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                && Math.hypot(e.getX() - downX, e.getY() - downY)
                   < ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
            double mx = (e.getX() - ox) / scale + minX;
            double my = (e.getY() - oy) / scale + minY;
            tapListener.onTap(fx * mx, fy * my);   // fx = ±1 is its own inverse
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
