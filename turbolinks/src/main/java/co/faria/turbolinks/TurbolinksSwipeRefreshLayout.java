package co.faria.turbolinks;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * <p>Custom SwipeRefreshLayout for Turbolinks.</p>
 */
class TurbolinksSwipeRefreshLayout extends SwipeRefreshLayout {
    private TurbolinksSwipeRefreshLayoutCallback callback;
    private OnChildScrollUpCallback mChildScrollUpCallback;

    /**
     * <p>Constructor to match SwipeRefreshLayout</p>
     *
     * @param context Refer to SwipeRefreshLayout
     */
    TurbolinksSwipeRefreshLayout(Context context) {
        super(context);
    }

    /**
     * <p>Constructor to match SwipeRefreshLayout</p>
     *
     * @param context Refer to SwipeRefreshLayout
     * @param attrs   Refer to SwipeRefreshLayout
     */
    TurbolinksSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * <p>Sets the callback to be used in canChildScrollUp().</p>
     * <p>See canChildScrollUp() to see how it's used.</p>
     *
     * @param callback The custom callback to be set
     */
    void setSizeCallback(TurbolinksSwipeRefreshLayoutCallback callback) {
        this.callback = callback;
    }

    private WebView getWebView() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // we only care for a webView target
            if (child instanceof WebView) {
                return (WebView) child;
            }
        }
        return null;
    }

    @Override
    public boolean canChildScrollUp() {
        WebView webView = getWebView();
        if (mChildScrollUpCallback != null) {
            return mChildScrollUpCallback.canChildScrollUp(this, webView);
        }
        // no callback registered, ask webView directly.
        // we don't care for other child views
        if (webView != null) {
            return (webView.getScrollY() > 0);
        }
        return true; // ignore pullToRefresh
    }

    @Override
    public void setOnChildScrollUpCallback(@Nullable OnChildScrollUpCallback callback) {
        mChildScrollUpCallback = callback;
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Handler handler = new Handler(this.getContext().getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onSwipeRefreshLayoutSizeChanged(w, h, oldw, oldh);
                }
            }
        });
    }
}
