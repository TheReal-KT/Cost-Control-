package com.example.agentcostcontrol.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.example.agentcostcontrol.R;

/** Original spending-control motif with one finite entrance, respecting Android's motion setting. */
final class AuthHeroView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Drawable[] brands;
    private ValueAnimator animator;
    private float reveal = 1f;

    AuthHeroView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        brands = new Drawable[]{ContextCompat.getDrawable(context, R.drawable.ic_brand_netflix),
                ContextCompat.getDrawable(context, R.drawable.ic_brand_openai),
                ContextCompat.getDrawable(context, R.drawable.ic_brand_spotify)};
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(650);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            reveal = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        reveal = 1f;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = Math.min(getWidth() / 320f, getHeight() / 150f);
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(unit, unit);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(Ui.color(getContext(), R.color.card_outline));
        bounds.set(-128, -58, 128, 58);
        canvas.drawOval(bounds, paint);
        paint.setStrokeWidth(8);
        paint.setColor(Ui.color(getContext(), R.color.brand_blue_soft));
        canvas.drawCircle(0, 0, 48, paint);
        paint.setColor(Ui.color(getContext(), R.color.brand_blue));
        bounds.set(-48, -48, 48, 48);
        canvas.drawArc(bounds, -90, 285 * reveal, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.color(getContext(), R.color.text_primary));
        for (int i = 0; i < 3; i++) {
            float x = -23 + i * 18;
            bounds.set(x, 22 - (i + 1) * 14 * reveal, x + 10, 22);
            canvas.drawRoundRect(bounds, 4, 4, paint);
        }
        float[][] positions = {{-120, -30}, {112, -32}, {94, 48}};
        for (int i = 0; i < brands.length; i++) {
            float x = positions[i][0], y = positions[i][1] + (1 - reveal) * 14;
            paint.setColor(Ui.color(getContext(), R.color.surface_raised));
            canvas.drawCircle(x, y, 23, paint);
            Drawable brand = brands[i];
            if (brand != null) {
                brand.setAlpha(Math.round(255 * reveal));
                brand.setBounds(Math.round(x - 12), Math.round(y - 12),
                        Math.round(x + 12), Math.round(y + 12));
                brand.draw(canvas);
            }
        }
        canvas.restore();
    }
}
