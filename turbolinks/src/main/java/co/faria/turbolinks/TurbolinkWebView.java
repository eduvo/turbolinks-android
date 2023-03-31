package co.faria.turbolinks;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.webkit.WebView;

public class TurbolinkWebView extends WebView {
    static final int SCROLL_EVENT_TIMEOUT_MIN = 250;
    static final int SCROLL_EVENT_TIMEOUT_MAX = 500;

    public interface OnScrollingFinishedListener {
        void onScrollFinished();
    }

    public interface OnScrollViewIdleListener {
        void scrollViewIdleEvent();
    }

    public interface OnYClampedListener {
        void onYClamped();
    }

    private final Debounce mDebounce = new Debounce();
    private boolean mIsScrollingActive;
    private boolean mIsTouchActive;
    private int mScrollRangeX;
    private int mScrollRangeY;
    private VelocityTracker mVelocityTracker;
    private OnScrollingFinishedListener mOnScrollingFinishedListener;
    private OnScrollViewIdleListener mOnScrollViewIdleListener;
    private OnYClampedListener mOnYClampedListener;

    public TurbolinkWebView(Context context) {
        super(context);
    }

    public boolean isScrollingActive() {
        return mIsScrollingActive;
    }

    public int getScrollRangeX() {
        return mScrollRangeX;
    }

    public int getScrollRangeY() {
        return mScrollRangeY;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mIsTouchActive = true;
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    mVelocityTracker.clear();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsTouchActive = false;
                mVelocityTracker.clear();

                if (!mIsScrollingActive) {
                    if (mOnScrollViewIdleListener != null) {
                        mOnScrollViewIdleListener.scrollViewIdleEvent();
                    }
                }
                break;
            default:
                break;
        }
        // do your stuff here... the below call will make sure the touch also goes to the webview.
        return super.onTouchEvent(event);
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);

        if (clampedY) {
            if (mOnYClampedListener != null) {
                mOnYClampedListener.onYClamped();
            }

            Runnable runable = new Runnable() {
                @Override
                public void run() {
                    mIsScrollingActive = false;
                    if (mOnScrollingFinishedListener != null) {
                        mOnScrollingFinishedListener.onScrollFinished();
                    }
                    if (!mIsTouchActive && (mOnScrollViewIdleListener != null)) {
                        mOnScrollViewIdleListener.scrollViewIdleEvent();
                    }
                }
            };

            mDebounce.clearCallback();
            runable.run();
        }
    }

    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        mScrollRangeX = scrollRangeX;
        mScrollRangeY = scrollRangeY;

        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        if (mIsScrollingActive == false) {
            mIsScrollingActive = true;
            mDebounce.interval = Math.max(200, Math.abs(t - oldt) * 3);
        }

        Runnable runable = new Runnable() {
            @Override
            public void run() {
                mIsScrollingActive = false;
                if (mOnScrollingFinishedListener != null) {
                    mOnScrollingFinishedListener.onScrollFinished();
                }

                if (!mIsTouchActive && (mOnScrollViewIdleListener != null)) {
                    mOnScrollViewIdleListener.scrollViewIdleEvent();
                }
            }
        };

        mDebounce.attempt(runable);
        super.onScrollChanged(l, t, oldl, oldt);
    }

    public OnScrollingFinishedListener getOnScrollingFinishedListener() {
        return mOnScrollingFinishedListener;
    }

    public void setOnScrollingFinishedListener(OnScrollingFinishedListener mOnScrollingFinishedListener) {
        this.mOnScrollingFinishedListener = mOnScrollingFinishedListener;
    }

    public OnScrollViewIdleListener getOnScrollViewIdleListener() {
        return mOnScrollViewIdleListener;
    }

    public void setOnScrollViewIdleListener(OnScrollViewIdleListener mOnScrollViewIdleListener) {
        this.mOnScrollViewIdleListener = mOnScrollViewIdleListener;
    }

    public OnYClampedListener getOnYClampedListener() {
        return mOnYClampedListener;
    }

    public void setOnYClampedListener(OnYClampedListener mOnYClampedListener) {
        this.mOnYClampedListener = mOnYClampedListener;
    }

    public float getCurrentTouchYVelocity() {
        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000);  // unit: pixels per second
            return mVelocityTracker.getYVelocity();
        }
        return 0;
    }

    public float getCurrentTouchXVelocity() {
        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000);  // unit: pixels per second
            return mVelocityTracker.getXVelocity();
        }
        return 0;
    }

    public boolean getIsTouchActive() {
        return mIsTouchActive;
    }

}
