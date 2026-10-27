package com.ultradim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * 浮球：拖动上下调节不透明度，双击开关，长按隐藏。
 * 它同时是「安全逃生通道」：当遮罩不透明度超过 80% 导致触摸被系统拦截时，
 * 浮球窗口位于遮罩之上，仍然可以接收触摸，把亮度调回来。
 */
public class KnobView extends View {

    public interface Callback {
        void onOpacityChanged(int value);

        void onToggle();

        void onDismiss();
    }

    private static final int MAX = 95;

    private final Callback callback;
    private final GestureDetector gesture;
    private final int sizePx;
    private final float density;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private int value = 40;
    private float downRawY;
    private int startValue;
    private boolean dragged;

    public KnobView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        this.density = getResources().getDisplayMetrics().density;
        this.sizePx = Math.round(72 * density);

        bgPaint.setColor(0xE614161C);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f * density);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(0xFF5B8DEF);
        textPaint.setColor(0xFFE8EAF0);
        textPaint.setTextSize(14f * density);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);

        gesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (KnobView.this.callback != null) KnobView.this.callback.onToggle();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!dragged && KnobView.this.callback != null) KnobView.this.callback.onDismiss();
            }
        });
    }

    public void setOpacity(int value) {
        this.value = Math.max(0, Math.min(MAX, value));
        invalidate();
    }

    public int getOpacity() {
        return value;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(sizePx, sizePx);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = sizePx / 2f;
        float cy = sizePx / 2f;
        float r = sizePx / 2f - 6f * density;
        canvas.drawCircle(cx, cy, r, bgPaint);

        ringPaint.setAlpha(48);
        canvas.drawCircle(cx, cy, r, ringPaint);
        ringPaint.setAlpha(255);
        arcRect.set(cx - r, cy - r, cx + r, cy + r);
        float sweep = 360f * value / MAX;
        if (sweep > 0f) {
            canvas.drawArc(arcRect, -90f, sweep, false, ringPaint);
        }

        canvas.drawText(value + "%", cx, cy + 5f * density, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        gesture.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawY = ev.getRawY();
                startValue = value;
                dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = downRawY - ev.getRawY();
                if (Math.abs(dy) > 6f * density) dragged = true;
                if (dragged) {
                    int delta = (int) (dy / (2.5f * density));
                    int next = Math.max(0, Math.min(MAX, startValue + delta));
                    if (next != value) {
                        value = next;
                        invalidate();
                        if (callback != null) callback.onOpacityChanged(value);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragged = false;
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }
}
