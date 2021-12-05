package co.faria.turbolinks;

/**
 * <p>Defines a callback for determining whether or not a child view can scroll up.</p>
 */
interface TurbolinksSwipeRefreshLayoutCallback {
    void onSwipeRefreshLayoutSizeChanged(int w, int h, int oldw, int oldh);
}
