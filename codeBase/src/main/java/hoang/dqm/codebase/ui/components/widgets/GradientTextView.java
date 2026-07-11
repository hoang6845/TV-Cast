package hoang.dqm.codebase.ui.components.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;

import hoang.dqm.codebase.R;


public class GradientTextView extends androidx.appcompat.widget.AppCompatTextView {
    private static final float DEFAULT_STROKE_WIDTH = 10f;
    private static final float DEFAULT_GLOW_ALPHA = 0.6f;
    
    private final Paint gradientPaint = new Paint();
    private final Paint glowPaint = new Paint();
    private final Rect textBounds = new Rect();
    
    private int[] gradientColors;
    private float[] gradientPositions;
    private float strokeWidth = DEFAULT_STROKE_WIDTH;
    private boolean hasGlow = true;
    private int glowColor;
    private float glowAlpha = DEFAULT_GLOW_ALPHA;

    public GradientTextView(Context context) {
        this(context, null, 0);
    }

    public GradientTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GradientTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
        // Giá trị mặc định
        gradientColors = new int[]{
            0xFFFFE29F,  // #FFE29F
            0xFFFFA99F,  // #FFA99F
            0xFFFF719A   // #FF719A
        };
        gradientPositions = new float[]{0f, 0.48f, 1f};
        glowColor = 0xFFFF719A;
        
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GradientTextView);
            
            // Đọc các thuộc tính tùy chỉnh
            strokeWidth = a.getFloat(R.styleable.GradientTextView_gradientStrokeWidth, 
                    DEFAULT_STROKE_WIDTH);
            hasGlow = a.getBoolean(R.styleable.GradientTextView_gradientHasGlow, true);
            glowAlpha = a.getFloat(R.styleable.GradientTextView_gradientGlowAlpha, 
                    DEFAULT_GLOW_ALPHA);
            
            // Đọc màu gradient (nếu được định nghĩa)
            int startColor = a.getColor(R.styleable.GradientTextView_gradientStartColor, 
                    gradientColors[0]);
            int centerColor = a.getColor(R.styleable.GradientTextView_gradientCenterColor, 
                    gradientColors[1]);
            int endColor = a.getColor(R.styleable.GradientTextView_gradientEndColor, 
                    gradientColors[2]);
            
            gradientColors = new int[]{startColor, centerColor, endColor};
            glowColor = a.getColor(R.styleable.GradientTextView_gradientGlowColor, endColor);
            
            a.recycle();
        }
        
        strokeWidth = dpToPx(context, strokeWidth);
        init();
        updateGradient();
    }

    private void init() {
        gradientPaint.setAntiAlias(true);
        gradientPaint.setTextSize(getTextSize());
        gradientPaint.setTypeface(getTypeface());
        
        glowPaint.setAntiAlias(true);
        glowPaint.setTextSize(getTextSize());
        glowPaint.setTypeface(getTypeface());
        glowPaint.setColor(glowColor);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setShadowLayer(15f, 0f, 0f, glowColor);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGradient();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        updateGradient();
    }

    private void updateGradient() {
        if (gradientPaint == null || glowPaint == null) return;

        String text = getText().toString();
        if (text.isEmpty()) return;
        
        // Lấy kích thước text
        gradientPaint.setTextSize(getTextSize());
        gradientPaint.getTextBounds(text, 0, text.length(), textBounds);

        // Tạo gradient từ trên xuống dựa trên bounds của text
        LinearGradient gradient = new LinearGradient(
            0f,
            textBounds.top,
            0f,
            textBounds.bottom,
            gradientColors,
            gradientPositions,
            Shader.TileMode.CLAMP
        );
        
        gradientPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String text = getText().toString();
        text = text.toUpperCase();
        if (text.isEmpty()) {
            super.onDraw(canvas);
            return;
        }
        
        // Tính toán vị trí vẽ text
        float x = getPaddingLeft();
        float y = getHeight() / 2f - ((gradientPaint.descent() + gradientPaint.ascent()) / 2f);
        
        // Vẽ glow effect
        if (hasGlow) {
            glowPaint.setAlpha((int) (255 * glowAlpha));
            canvas.drawText(text, x, y, glowPaint);
        }
        
        // Vẽ stroke (viền)
        if (strokeWidth > 0) {
            gradientPaint.setStyle(Paint.Style.STROKE);
            gradientPaint.setStrokeWidth(strokeWidth);
            canvas.drawText(text, x, y, gradientPaint);
        }
        
        // Vẽ fill (bên trong)
        gradientPaint.setStyle(Paint.Style.FILL);
        canvas.drawText(text, x, y, gradientPaint);
    }

    // Các phương thức setter để thay đổi thuộc tính động
    public void setGradientColors(int[] colors, float[] positions) {
        if (colors != null && positions != null && colors.length == positions.length) {
            this.gradientColors = colors;
            this.gradientPositions = positions;
            updateGradient();
            invalidate();
        }
    }

    public void setGradientColors(int startColor, int centerColor, int endColor) {
        this.gradientColors = new int[]{startColor, centerColor, endColor};
        this.gradientPositions = new float[]{0f, 0.48f, 1f};
        updateGradient();
        invalidate();
    }

    public void setStrokeWidth(float strokeWidth) {
        this.strokeWidth = dpToPx(getContext(), strokeWidth);
        invalidate();
    }

    public void setHasGlow(boolean hasGlow) {
        this.hasGlow = hasGlow;
        invalidate();
    }

    public void setGlowColor(int color) {
        this.glowColor = color;
        glowPaint.setColor(color);
        glowPaint.setShadowLayer(15f, 0f, 0f, color);
        invalidate();
    }

    public void setGlowAlpha(float alpha) {
        this.glowAlpha = Math.max(0f, Math.min(1f, alpha));
        invalidate();
    }

    public static int dpToPx(Context context, float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}