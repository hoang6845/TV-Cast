package hoang.dqm.codebase.ui.components.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.os.Build;
import android.util.AttributeSet;

import hoang.dqm.codebase.R;

public class StrokedTextView extends androidx.appcompat.widget.AppCompatTextView {
    private static final float DEFAULT_STROKE_WIDTH = 0;
    private final Paint mOutlinePaint = new Paint();
    private final Path mOutlinePath = new Path();
    private final Path shadowPath = new Path();
    Paint shadowPaint = new Paint();

    private float _strokeWidth = 0f;
    private float _strokeColor = 0f;
    private Boolean _hasShadow = false;
    private final Matrix matrix = new Matrix();


    public StrokedTextView(Context context) {
        this(context, null, 0);
    }

    public StrokedTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StrokedTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.StrokedTextView);
            _strokeColor = a.getColor(R.styleable.StrokedTextView_textStrokeColor,
                    getCurrentTextColor());
            _hasShadow = a.getBoolean(R.styleable.StrokedTextView_textHasShadow,
                    false);
            _strokeWidth = a.getFloat(R.styleable.StrokedTextView_textStrokeWidth,
                    DEFAULT_STROKE_WIDTH);
            a.recycle();
        } else {
            _strokeColor = getCurrentTextColor();
            _strokeWidth = DEFAULT_STROKE_WIDTH;
        }
        //convert values specified in dp in XML layout to
        //px, otherwise stroke width would appear different
        //on different screens
        _strokeWidth = dpToPx(context, _strokeWidth);
        init();
    }

    private void init() {
        mOutlinePaint.setStrokeWidth(_strokeWidth);
        mOutlinePaint.setStyle(Paint.Style.STROKE);
        mOutlinePaint.setColor((int) _strokeColor);
        shadowPaint = new Paint(mOutlinePaint);
        shadowPaint.setAlpha(120);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        float xOffset = 0f;
        float baseline = 0f;
        try {
            xOffset = getLayout().getLineLeft(0) + getPaddingLeft();
            baseline = getLayout().getLineBaseline(0) + getPaddingTop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        getPaint().getTextPath(getText().toString(), 0, getText().length(), xOffset, baseline, mOutlinePath);
        shadowPath.op(mOutlinePath, Path.Op.UNION);
        matrix.postTranslate(0f, 8f);
        shadowPath.transform(matrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (_strokeWidth > 0) {
            canvas.save();
            // The following insures that we don't draw inside the characters.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                canvas.clipPath(mOutlinePath, Region.Op.DIFFERENCE);
            } else {
                canvas.clipOutPath(mOutlinePath);
            }
            canvas.drawPath(mOutlinePath, mOutlinePaint);
            canvas.restore();
        } else {
            if (_hasShadow) {
                canvas.drawPath(shadowPath, shadowPaint);
            }
        }
    }

    public void setStrokeWidth(Float strokeWidth) {
        _strokeWidth = dpToPx(getContext(), strokeWidth);
        mOutlinePaint.setStrokeWidth(_strokeWidth);
        invalidate();
    }

    public void setStrokeColor(int color) {
        _strokeColor = color;
    }

    public static int dpToPx(Context context, float dp) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}