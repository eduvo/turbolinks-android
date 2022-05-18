package co.faria.turbolinks;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.util.DisplayMetrics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import okhttp3.*;

import co.faria.turbolinks.R;

/**
 * <p>The main concrete class to use Turbolinks 5 in your app.</p>
 */

public class TurbolinksSession implements TurbolinksSwipeRefreshLayoutCallback, SwipeRefreshLayout.OnChildScrollUpCallback {
    // Is client side redirect for turbolink request with wrong referrer enabled?
    static final boolean FIX_TURBOLINK_REFERRER_REDIRECT_CLIENTSIDE = false;

    // ---------------------------------------------------
    // Package public vars (allows for greater flexibility and access for testing)
    // ---------------------------------------------------

    boolean initPageLoading; // During initPageLoading no bridge is injected
    boolean bridgeInjectionInProgress; // Ensures the bridge is only injected once
    boolean coldBootInProgress;
    boolean restoreWithCachedSnapshot;
    boolean turbolinksIsReady; // Script finished and TL fully instantiated
    boolean screenshotsEnabled;
    boolean pullToRefreshEnabled;
    boolean webViewAttachedToNewParent;
    boolean webViewClientAssigned;
    boolean invalidated;
    int progressIndicatorDelay;
    long previousOverrideTime;

    Activity activity;
    Fragment fragment;
    HashMap<String, Object> javascriptInterfaces = new HashMap<>();
    HashMap<String, String> restorationIdentifierMap = new HashMap<>();
    String location;
    String redirectLocation;
    String currentVisitIdentifier;
    TurbolinksAdapter turbolinksAdapter;
    TurbolinksView turbolinksView;
    View progressView;
    View progressIndicator;

    TurbolinksJSExecutionResultCallback executionResultCallback;

    static volatile TurbolinksSession defaultInstance;

    // ---------------------------------------------------
    // Final vars
    // ---------------------------------------------------

    static final String ACTION_ADVANCE = "advance";
    static final String ACTION_RESTORE = "restore";
    static final String ACTION_REPLACE = "replace";
    static final String JAVASCRIPT_INTERFACE_NAME = "TurbolinksNative";
    static final int PROGRESS_INDICATOR_DELAY = 250;

    final Context applicationContext;
    public WebView webView;
    public OkHttpClient client;

    // ---------------------------------------------------
    // Constructor
    // ---------------------------------------------------

    /**
     * Private constructor called to return a new Turbolinks instance.
     *
     * @param context Any Android context.
     */
    private TurbolinksSession(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null.");
        }

        this.applicationContext = context.getApplicationContext();
        this.screenshotsEnabled = true;
        this.pullToRefreshEnabled = true;
        this.webViewAttachedToNewParent = false;

        this.initWebView();
    }

    public String getCurrentVisitIdentifier() {
        return currentVisitIdentifier;
    }

    public void initWebView() {
        if (this.webView != null) {
            this.unmountWebClient();
        }
        this.webViewClientAssigned = false;
        mountWebClient();
    }

    public void setInitPageLoading(Boolean value) {
        this.initPageLoading = value;
    }

    public void mountWebClient() {
        if (!this.webViewClientAssigned) {
            webViewClientAssigned = true;

            if (FIX_TURBOLINK_REFERRER_REDIRECT_CLIENTSIDE) {
                client = new OkHttpClient.Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .cookieJar(new TLWebviewCookieHandler()).build();
            }

            resetToColdBoot();

            this.webView = TurbolinksHelper.createWebView(applicationContext);
            this.webView.addJavascriptInterface(this, JAVASCRIPT_INTERFACE_NAME);
            this.webView.clearCache(true);

            this.webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    coldBootInProgress = true;
                    bridgeInjectionInProgress = false;
                }

                @Override
                public void onPageFinished(WebView view, final String location) {
                    Log.d("TurbolinksSession", "onPageFinished (WebClient): " + location);

                    if (!location.equals("about:blank") && !initPageLoading) {
                        String jsCall = "window.webView == null";
                        webView.evaluateJavascript(jsCall, new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String s) {
                                if (Boolean.parseBoolean(s) && !bridgeInjectionInProgress) {
                                    bridgeInjectionInProgress = true;
                                    TurbolinksHelper.injectTurbolinksBridge(TurbolinksSession.this, applicationContext, webView);
                                    TurbolinksLog.d("Bridge injected");

                                    TurbolinksHelper.runOnMainThread(activity, new Runnable() {
                                        @Override
                                        public void run() {
                                            if (webView != null) { // make sure webView is available
                                                turbolinksAdapter.onPageFinished();
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    } else {
                        // notifiy page finished for initPageLoading
                        if (!location.equals("about:blank")) {
                            turbolinksAdapter.onPageFinished();
                        }
                    }
                }

                /**
                 * Turbolinks will not call adapter.visitProposedToLocationWithAction in some cases,
                 * like target=_blank or when the domain doesn't match. We still route those here.
                 * This is mainly only called when links within a webView are clicked and not during
                 * loadUrl. However, a redirect on a cold boot can also cause this to fire, so don't
                 * override in that situation, since Turbolinks is not yet ready.
                 * http://stackoverflow.com/a/6739042/3280911
                 */
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String location = request.getUrl().toString();

                    if (request.isRedirect()) {
                        if (turbolinksAdapter.requestRedirect(location)) {
                            hideProgressView(currentVisitIdentifier);
                            return false;
                        }
                    }

                    if (!turbolinksIsReady || coldBootInProgress) {
                        return true;
                    }

                    /**
                     * Prevents firing twice in a row within a few milliseconds of each other, which
                     * happens. So we check for a slight delay between requests, which is plenty of time
                     * to allow for a user to click the same link again.
                     */
                    long currentOverrideTime = new Date().getTime();
                    if ((currentOverrideTime - previousOverrideTime) > 500) {
                        previousOverrideTime = currentOverrideTime;
                        TurbolinksLog.d("Overriding load: " + location);
                        visitProposedToLocationWithAction(location, ACTION_ADVANCE);
                    }

                    return true;
                }

//                @Override
//                public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
//                    this.location = url;
//                    super.doUpdateVisitedHistory(view, url, isReload);
//                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request)
                {
                    // do we have a page request with differing Turbolinks-Referrer?
                    if (FIX_TURBOLINK_REFERRER_REDIRECT_CLIENTSIDE && (request.getUrl().toString().equals(location)) &&
                            request.getMethod().equals("GET") &&
                            request.getRequestHeaders().containsKey("Referer") &&
                            request.getUrl().toString().equals(request.getRequestHeaders().get("Referer")) &&
                            request.getRequestHeaders().containsKey("Turbolinks-Referrer") &&
                            !request.getRequestHeaders().get("Referer").equals(request.getRequestHeaders().get("Turbolinks-Referrer"))
                    ) {
                        Request.Builder builder = new Request.Builder().url(request.getUrl().toString());

                        for (Map.Entry<String, String> entry : request.getRequestHeaders().entrySet()) {
                            if (entry.getKey().equals("Referer")) {
                                // set correct Referer
                                builder.addHeader(entry.getKey(), request.getRequestHeaders().get("Turbolinks-Referrer"));
                            } else {
                                builder.addHeader(entry.getKey(), entry.getValue());
                            }
                        }
                        builder.addHeader("x-requested-with", applicationContext.getPackageName());
                        builder.addHeader("accept-language", Locale.getDefault().toLanguageTag() + ";" + Locale.getDefault().getLanguage());

                        Request newRequest = builder.build();
                        Response response = null;
                        try {
                            response = client.newCall(newRequest).execute();
                        } catch (IOException e) {
                            e.printStackTrace();
                            return super.shouldInterceptRequest(view, request);
                        }
                        // Redirect detected => assign redirectLocation to ignore subsequent invalidate page reset.
                        if (response.isRedirect() && (response.header("location") != null)) {
                            location = response.header("location");
                            redirectLocation = location;
                        }

                        InputStream responseInputStream = response.body().byteStream();
                        String contentTypeValue = response.body().contentType().type();
                        String encodingValue = response.body().contentType().charset().toString();
                        return new WebResourceResponse(contentTypeValue, encodingValue, responseInputStream);
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);

                    if (request.isForMainFrame()) {
                        resetToColdBoot();
                        turbolinksAdapter.onReceivedError(error.getDescription().toString(), error.getErrorCode());
                    }
                    TurbolinksLog.d("onReceivedError: Code: " + error.getErrorCode() + " onRequest: " + request.getUrl().toString());
                }

                @Override
                @TargetApi(Build.VERSION_CODES.M)
                public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                    super.onReceivedHttpError(view, request, errorResponse);

                    // if error is not for MainFrame -> fail silently
                    if (request.isForMainFrame()) {
                        resetToColdBoot();
                        turbolinksAdapter.onReceivedHttpError(errorResponse.getReasonPhrase(), errorResponse.getStatusCode());
                    }
                    TurbolinksLog.d("onReceivedHttpError: " + errorResponse.getStatusCode() + " onRequest: " + request.getUrl().toString());
                }
            });
        }
    }

    public void unmountWebClient() {
        webViewClientAssigned = false;

        clearWebView();

        webView.setWebViewClient(null);
        webView.setWebChromeClient(null);
        webView.setOnScrollChangeListener(null);

        webView = null;
        client = null;
    }

    // ---------------------------------------------------
    // Initialization
    // ---------------------------------------------------

    /**
     * Creates a brand new TurbolinksSession that the calling application will be responsible for
     * managing.
     *
     * @param context Any Android context.
     * @return TurbolinksSession to be managed by the calling application.
     */
    public static TurbolinksSession getNew(Context context) {
        TurbolinksLog.d("TurbolinksSession getNew called");
        return new TurbolinksSession(context);
    }

    public void clearWebView() {
        webView.clearHistory();

        // NOTE: clears RAM cache, if you pass true, it will also clear the disk cache.
        // Probably not a great idea to pass true if you have other WebViews still alive.
        webView.clearCache(true);

        try {
            // Loading a blank page is optional, but will ensure that the WebView isn't doing anything when you destroy it.
            webView.loadUrl("about:blank");
        } catch (Exception e) {
            TurbolinksLog.d("Loading blank page to clear failed: " + e.getMessage());
        }

        webView.onPause();
        webView.removeAllViews();
        webView.stopLoading();
    }

    public void cleanReferences() {
        if (webView != null) {
            unmountWebClient();
        }
        activity = null;
        fragment = null;
        turbolinksView = null;
    }

    /**
     * Convenience method that returns a default TurbolinksSession. This is useful for when an
     * app only needs one instance of a TurbolinksSession.
     *
     * @param context Any Android context.
     * @return The default, static instance of a TurbolinksSession, guaranteed to not be null.
     */
    public static TurbolinksSession getDefault(Context context) {
        if (defaultInstance == null) {
            synchronized (TurbolinksSession.class) {
                if (defaultInstance == null) {
                    TurbolinksLog.d("Default instance is null, creating new");
                    defaultInstance = TurbolinksSession.getNew(context);
                }
            }
        }

        return defaultInstance;
    }

    /**
     * Resets the default TurbolinksSession instance to null in case you want a fresh session.
     */
    public static void resetDefault() {
        defaultInstance = null;
    }

    /**
     * <p>Tells the logger whether to allow logging in debug mode.</p>
     *
     * @param enabled If true debug logging is enabled.
     */
    public static void setDebugLoggingEnabled(boolean enabled) {
        TurbolinksLog.setDebugLoggingEnabled(enabled);
    }

    // ---------------------------------------------------
    // Required chained methods
    // ---------------------------------------------------

    /**
     * <p><b>REQUIRED</b> Turbolinks requires a context for a variety of uses, and for maximum clarity
     * we ask for an Activity context instead of a generic one. (On occassion, we've run into WebView
     * bugs where an Activity is the only fix).</p>
     *
     * <p>It's best to pass a new activity to Turbolinks for each new visit for clarity. This ensures
     * there is a one-to-one relationship maintained between internal activity IDs and visit IDs.</p>
     *
     * @param activity An Android Activity, one per visit.
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession activity(Activity activity) {
        if (activity != this.activity) {
            // reassign webView
            //if (this.activity != null) {
            //    initWebView();
            //}

            this.activity = activity;

            Context webViewContext = webView.getContext();
            if (webViewContext instanceof MutableContextWrapper) {
                ((MutableContextWrapper) webViewContext).setBaseContext(this.activity);
            }

            this.webView.setWebChromeClient(null);

            webView.getSettings().setSupportMultipleWindows(true);
            this.webView.setWebChromeClient(new TLChromeClientWithFileChooser(this.activity, this.turbolinksAdapter));

            // reassign progressView
            if (progressView != null) {
                progressView = LayoutInflater.from(activity).inflate(R.layout.turbolinks_progress, turbolinksView, false);

                progressIndicator = progressView.findViewById(R.id.turbolinks_default_progress_indicator);
                progressIndicatorDelay = PROGRESS_INDICATOR_DELAY;
            }
        }

        return this;
    }

    /**
     * <p><b>REQUIRED</b> Turbolinks requires a context for a variety of uses, and for maximum clarity
     * we ask for an Fragment context instead of a generic one. (On occassion, we've run into WebView
     * bugs where an Fragment is the only fix).</p>
     *
     * <p>It's best to pass a new Fragment to Turbolinks for each new visit for clarity. This ensures
     * there is a one-to-one relationship maintained between internal Fragment IDs and visit IDs.</p>
     *
     * @param fragment An Android fragment, one per visit.
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession fragment(Fragment fragment) {
        this.fragment = fragment;
        if ((fragment != null) && this.activity != fragment.getActivity()) {
            activity(fragment.getActivity());
        }

        return this;
    }

    /**
     * <p><b>REQUIRED</b> A {@link TurbolinksAdapter} implementation is required so that callbacks
     * during the Turbolinks event lifecycle can be passed back to your app.</p>
     *
     * @param turbolinksAdapter Any class that implements {@link TurbolinksAdapter}.
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession adapter(TurbolinksAdapter turbolinksAdapter) {
        this.turbolinksAdapter = turbolinksAdapter;
        return this;
    }

    /**
     * <p><b>REQUIRED</b> A {@link TurbolinksView} object that's been inflated in a custom layout is
     * required so the library can manage various view-related tasks: attaching/detaching the
     * internal webView, showing/hiding a progress loading view, etc.</p>
     *
     * @param turbolinksView An inflated TurbolinksView from your custom layout.
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession view(TurbolinksView turbolinksView) {
        if (this.turbolinksView != null) {
            this.turbolinksView.setOnScrollChangeListener(null);
        }

        if (this.turbolinksView != turbolinksView) {
            this.turbolinksView = turbolinksView;

            if (turbolinksView != null) {
                this.webViewAttachedToNewParent = this.turbolinksView.attachWebView(webView, screenshotsEnabled, pullToRefreshEnabled);
                this.turbolinksView.getRefreshLayout().setOnChildScrollUpCallback(this);
                this.turbolinksView.getRefreshLayout().setSizeCallback(this);
                this.turbolinksView.getRefreshLayout().setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                    @Override
                    public void onRefresh() {
                        resetToColdBoot();
                        if (location != null) {
                            turbolinksAdapter.visitProposedToLocationWithAction(location, ACTION_REPLACE);
                        } else {
                            TurbolinksLog.e("visitProposedToLocationWithAction called with NULL location!");
                        }
                    }
                });
            }
        }

        return this;
    }

    /**
     * <p><b>REQUIRED</b> Executes a Turbolinks visit. Must be called at the end of the chain --
     * all required parameters will first be validated before firing.</p>
     *
     * @param location The URL to visit.
     */
    public void visit(String location) {
        TurbolinksLog.d("visit called: " + location);
        this.invalidated = false;
        this.location = location;
        this.redirectLocation = "";

        validateRequiredParams();

        turbolinksAdapter.visitStarted(location);

        if (!turbolinksIsReady || webViewAttachedToNewParent) {
            initProgressView();
        }

        if (turbolinksIsReady) {
            visitCurrentLocationWithTurbolinks();
        } else {
            webView.stopLoading();
            TurbolinksLog.d("Cold booting: " + location);
            webView.loadUrl(location);
        }

        // Reset so that cached snapshot is not the default for the next visit
        restoreWithCachedSnapshot = false;
    }

    // ---------------------------------------------------
    // Optional chained methods
    // ---------------------------------------------------

    /**
     * <p><b>Optional</b> This will override the default progress view/progress indicator that's provided
     * out of the box. This allows you to customize how you want the progress view to look while
     * pages are loading.</p>
     *
     * @param progressView           A custom progressView object.
     * @param progressIndicatorResId The resource ID of a progressIndicator object inside the progressView.
     * @param progressIndicatorDelay The delay, in milliseconds, before the progress indicator should be displayed
     *                               inside the progress view (default is 500 ms).
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession progressView(View progressView, int progressIndicatorResId, int progressIndicatorDelay) {
        this.progressView = progressView;
        this.progressIndicator = progressView.findViewById(progressIndicatorResId);
        this.progressIndicatorDelay = progressIndicatorDelay;

        if (this.progressIndicator == null) {
            throw new IllegalArgumentException("A progress indicator view must be provided in your custom progressView.");
        }

        return this;
    }

    /**
     * <p><b>Optional</b> By default Turbolinks will "advance" to the next page and scroll position
     * will not be restored. Optionally calling this method allows you to set the behavior on a
     * per-visitbasis. This will be reset to "false" after each visit.</p>
     *
     * @param restoreWithCachedSnapshot If true, will restore scroll position. If false, will not restore
     *                                  scroll position.
     * @return The TurbolinksSession to continue the chained calls.
     */
    public TurbolinksSession restoreWithCachedSnapshot(boolean restoreWithCachedSnapshot) {
        this.restoreWithCachedSnapshot = restoreWithCachedSnapshot;
        return this;
    }

    // ---------------------------------------------------
    // TurbolinksNative adapter methods
    // ---------------------------------------------------

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks when a new visit is initiated from a
     * webView link.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param location URL to be visited.
     * @param action   Whether to treat the request as an advance (navigating forward) or a replace (back).
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitProposedToLocationWithAction(final String location, final String action) {
        TurbolinksLog.d("visitProposedToLocationWithAction called");

        if (location != null) {
            TurbolinksHelper.runOnMainThread(activity, new Runnable() {
                @Override
                public void run() {
                    turbolinksAdapter.visitProposedToLocationWithAction(location, action != null ? action : ACTION_ADVANCE);
                }
            });
        } else {
            TurbolinksLog.e("visitProposedToLocationWithAction called with NULL location!");
        }
    }

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks when a new visit has just started.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param visitIdentifier        A unique identifier for the visit.
     * @param visitHasCachedSnapshot Whether the visit has a cached snapshot available.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitStarted(String visitIdentifier, boolean visitHasCachedSnapshot) {
        TurbolinksLog.d("visitStarted called");

        currentVisitIdentifier = visitIdentifier;

        runJavascript("webView.changeHistoryForVisitWithIdentifier", visitIdentifier);
        runJavascript("webView.issueRequestForVisitWithIdentifier", visitIdentifier);
        runJavascript("webView.loadCachedSnapshotForVisitWithIdentifier", visitIdentifier);
    }

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks when the HTTP request has been
     * completed.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param visitIdentifier A unique identifier for the visit.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitRequestCompleted(String visitIdentifier) {
        TurbolinksLog.d("visitRequestCompleted called");

        if (TextUtils.equals(visitIdentifier, currentVisitIdentifier)) {
            runJavascript("webView.loadResponseForVisitWithIdentifier", visitIdentifier);
        }
    }

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks when the HTTP request has failed.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param visitIdentifier A unique identifier for the visit.
     * @param statusCode      The HTTP status code that caused the failure.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitRequestFailedWithStatusCode(final String visitIdentifier, final int statusCode) {
        TurbolinksLog.d("visitRequestFailedWithStatusCode called");
        hideProgressView(visitIdentifier);

        if (TextUtils.equals(visitIdentifier, currentVisitIdentifier)) {
            TurbolinksHelper.runOnMainThread(activity, new Runnable() {
                @Override
                public void run() {
                    turbolinksAdapter.requestFailedWithStatusCode(statusCode);
                }
            });
        }
        // we need to reinitialise turbolinks
        resetToColdBoot();
    }

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks once the page has been fully rendered
     * in the webView.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param visitIdentifier A unique identifier for the visit.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitRendered(final String visitIdentifier) {
        TurbolinksHelper.runOnMainThread(activity, new Runnable() {
            @Override
            public void run() {
                turbolinksAdapter.webViewRendered(new Runnable() {
                    @Override
                    public void run() {
                        TurbolinksLog.d("visitRendered called, hiding progress view for identifier: " + visitIdentifier);
                        hideProgressView(visitIdentifier);
                    }
                });
            }
        });
    }

    /**
     * <p><b>JavascriptInterface only</b> Called by Turbolinks when the visit is fully completed --
     * request successful and page rendered.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     *
     * @param visitIdentifier       A unique identifier for the visit.
     * @param restorationIdentifier A unique identifier for restoring the page and scroll position
     *                              from cache.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void visitCompleted(final String visitIdentifier, String restorationIdentifier) {
        TurbolinksLog.d("visitCompleted called");
        if (this.invalidated) {
            TurbolinksLog.d("VISIT was invalidated -> skip completed");
            return;
        }

        addRestorationIdentifierToMap(restorationIdentifier);

        if (TextUtils.equals(visitIdentifier, currentVisitIdentifier)) {
            TurbolinksHelper.runOnMainThread(activity, new Runnable() {
                @Override
                public void run() {
                    turbolinksAdapter.visitCompleted(visitIdentifier);
                    if (turbolinksView != null) {
                        turbolinksView.getRefreshLayout().setRefreshing(false);
                    }
                }
            });
        }
    }

    /**
     * <p><b>JavascriptInterface only</b> Called when Turbolinks detects that the page being visited
     * has been invalidated, typically by new resources in the the page HEAD.</p>
     *
     * <p>Warning: This method is public so it can be used as a Javascript Interface. you should
     * never call this directly as it could lead to unintended behavior.</p>
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void pageInvalidated() {
        TurbolinksLog.d("pageInvalidated called");
        this.invalidated = true;

        // in case we have a pending redirect request, this is called also
        // we don't invalidate page, otherwise we get two requests.
        if (!redirectLocation.equals(location)) {
            resetToColdBoot();
        }

        TurbolinksHelper.runOnMainThread(activity, new Runnable() {
            @Override
            public void run() { // route through normal chain so progress view is shown, regular logging, etc.
                turbolinksAdapter.pageInvalidated();
                visit(location);
            }
        });
    }

    // ---------------------------------------------------
    // TurbolinksNative helper methods
    // ---------------------------------------------------

    /**
     * <p><b>JavascriptInterface only</b> Hides the progress view when the page is fully rendered.</p>
     *
     * <p>Note: This method is public so it can be used as a Javascript Interface. For all practical
     * purposes, you should never call this directly.</p>
     *
     * @param visitIdentifier A unique identifier for the visit.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void hideProgressView(final String visitIdentifier) {
        TurbolinksHelper.runOnMainThread(activity, new Runnable() {
            @Override
            public void run() {
                /**
                 * pageInvalidated will cold boot, but another in-flight response from
                 * visitResponseLoaded could attempt to hide the progress view. Checking
                 * turbolinksIsReady ensures progress view isn't hidden too soon by the non cold boot.
                 */
                if (turbolinksIsReady && (turbolinksView != null) && TextUtils.equals(visitIdentifier, currentVisitIdentifier)) {
                    TurbolinksLog.d("Hiding progress view for visitIdentifier: " + visitIdentifier + ", currentVisitIdentifier: " + currentVisitIdentifier);
                    turbolinksView.hideProgress();
                    turbolinksView.hideScreenshot();
                }
            }
        });
    }

    /**
     * <p><b>JavascriptInterface only</b> Sets internal flags that indicate whether Turbolinks in
     * the webView is ready for use.</p>
     *
     * <p>Note: This method is public so it can be used as a Javascript Interface. For all practical
     * purposes, you should never call this directly.</p>
     *
     * @param turbolinksIsReady The Javascript bridge checks the current page for Turbolinks, and
     *                          sends the results of that check here.
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void setTurbolinksIsReady(boolean turbolinksIsReady) {
        this.turbolinksIsReady = turbolinksIsReady;

        if (turbolinksIsReady) {
            bridgeInjectionInProgress = false;

            TurbolinksHelper.runOnMainThread(activity, new Runnable() {
                @Override
                public void run() {
                    if (fragment != null) {
                        TurbolinksLog.d("TurbolinksSession is ready");
                        visitCurrentLocationWithTurbolinks();
                    }
                }
            });

            coldBootInProgress = false;
        } else {
            TurbolinksLog.d("TurbolinksSession is not ready. Resetting and throw error.");
            resetToColdBoot();
            visitRequestFailedWithStatusCode(currentVisitIdentifier, 500);
        }
    }

    /**
     * <p><b>JavascriptInterface only</b> Handles the error condition when reaching a page without
     * Turbolinks.</p>
     *
     * <p>Note: This method is public so it can be used as a Javascript Interface. For all practical
     * purposes, you should never call this directly.</p>
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void turbolinksDoesNotExist() {
        TurbolinksHelper.runOnMainThread(activity, new Runnable() {
            @Override
            public void run() {
                TurbolinksLog.d("Error instantiating turbolinks_bridge.js - resetting to cold boot.");
                resetToColdBoot();
                if (turbolinksView != null) {
                    turbolinksView.hideProgress();
                }
            }
        });
    }


    /**
     * Execute javascript result callback. </p>
     */
    @SuppressWarnings("unused")
    @android.webkit.JavascriptInterface
    public void executeResultCallback(String result) {
        if (executionResultCallback != null) {
            TurbolinksJSExecutionResultCallback currentCallback = executionResultCallback;
            executionResultCallback = null;
            if (result.startsWith("\"JSError:")) {
                currentCallback.executed(false, null, new Error(result.replaceFirst("\"JSError:", "")));
            } else {
                currentCallback.executed(true, result, null);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public
    // -----------------------------------------------------------------------

    /**
     * <p>Provides the ability to add an arbitrary number of custom Javascript Interfaces to the built-in
     * Turbolinks webView.</p>
     *
     * @param object The object with annotated JavascriptInterface methods
     * @param name   The unique name for the interface (must not use the reserved name "TurbolinksNative")
     */
    @SuppressLint("JavascriptInterface")
    public void addJavascriptInterface(Object object, String name) {
        if (TextUtils.equals(name, JAVASCRIPT_INTERFACE_NAME)) {
            throw new IllegalArgumentException(JAVASCRIPT_INTERFACE_NAME + " is a reserved Javascript Interface name.");
        }

        if (javascriptInterfaces.get(name) == null) {
            javascriptInterfaces.put(name, object);
            webView.addJavascriptInterface(object, name);

            TurbolinksLog.d("Adding JavascriptInterface: " + name + " for " + object.getClass().toString());
        }
    }

    /**
     * <p>Returns the fragment attached to the Turbolinks call.</p>
     *
     * @return The attached fragment.
     */
    public Fragment getFragment() {
        return fragment;
    }

    /**
     * <p>Returns the activity attached to the Turbolinks call.</p>
     *
     * @return The attached activity.
     */
    public Activity getActivity() {
        return activity;
    }


    /**
     * <p>Returns the internal WebView used by Turbolinks.</p>
     *
     * @return The WebView used by Turbolinks.
     */
    public WebView getWebView() {
        return webView;
    }

    /**
     * <p>Hides current screenshot.</p>
     */
    public void hideScreenshot() {
        this.turbolinksView.hideScreenshot();
    }

    /**
     * <p>Show progress view</p>
     */
    public void showProgressView() {
        if (this.turbolinksView != null) {
            initProgressView();
        }
    }

    /**
     * <p>Resets the TurbolinksSession to go through the full cold booting sequence (full page load)
     * on the next Turbolinks visit.</p>
     */
    public void resetToColdBoot() {
        TurbolinksLog.d("ResetToColdBoot");
        bridgeInjectionInProgress = false;
        turbolinksIsReady = false;
        coldBootInProgress = false;
    }

    /**
     * <p>Runs a Javascript function with any number of arbitrary params in the Turbolinks webView.</p>
     *
     * @param functionName The name of the function, without any parenthesis or params
     * @param params       A comma delimited list of params. Params will be automatically JSONified.
     */
    public void runJavascript(final String functionName, final Object... params) {
        TurbolinksHelper.runJavascript(applicationContext, webView, functionName, params);
    }

    /**
     * <p>Runs raw Javascript in webView. Simply wraps the loadUrl("javascript: methodName()") call.</p>
     *
     * @param rawJavascript The full Javascript string that will be executed by the WebView.
     */
    public void runJavascriptRaw(String rawJavascript) {
        TurbolinksHelper.runJavascriptRaw(applicationContext, webView, rawJavascript);
    }

    /**
     * <p>Determines whether screenshots are displayed (instead of a progress view) when resuming
     * an activity. Default is true.</p>
     *
     * @param enabled If true automatic screenshotting is enabled.
     */
    public void setScreenshotsEnabled(boolean enabled) {
        screenshotsEnabled = enabled;
    }

    /**
     * <p>Determines whether WebViews can be refreshed by pulling/swiping from the top
     * of the WebView. Default is true.</p>
     *
     * @param enabled If true pulling to refresh the WebView is enabled
     */
    public void setPullToRefreshEnabled(boolean enabled) {
        pullToRefreshEnabled = enabled;
    }

    /**
     * <p>Provides the status of whether Turbolinks is initialized and ready for use.</p>
     *
     * @return True if Turbolinks has been fully loaded and detected on the page.
     */
    public boolean turbolinksIsReady() {
        return turbolinksIsReady;
    }

    /**
     * <p>A convenience method to fire a Turbolinks visit manually.</p>
     *
     * @param location URL to visit.
     * @param action   Whether to treat the request as an advance (navigating forward) or a replace (back).
     */
    public void visitLocationWithAction(String location, String action) {
        this.location = location;
        runJavascript("webView.visitLocationWithActionAndRestorationIdentifier", TurbolinksHelper.encodeUrl(location), action, getRestorationIdentifierFromMap());
    }

    // Execute script
    public void runJavascriptWithEvalResult(String script, @Nullable final TurbolinksJSExecutionResultCallback callback) {
        String evalCode = "var _result = null; var code = (function () {/*{ " + script + " }*/}).toString().match(/[^]*\\/\\*\\{([^]*)\\}\\*\\/\\}$/)[1]; try { eval(code) } catch (e) { 'JSError:' + e.message; }";
        webView.evaluateJavascript(evalCode, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String result) {
                if (callback != null) {
                    if (result.startsWith("\"JSError:")) {
                        callback.executed(false, null, new Error(result.replaceFirst("\"JSError:", "")));
                    } else {
                        callback.executed(true, result, null);
                    }
                } else {
                    if (result.startsWith("\"JSError:")) {
                        TurbolinksLog.d("Script executed with error: " + result.replaceFirst("\"JSError:", ""));
                    } else {
                        TurbolinksLog.d("Script executed, without callback");
                    }
                }
            }
        });
    }

    // Execute script
    public void runJavascriptWithResultAfterFrameRequested(String script, TurbolinksJSExecutionResultCallback callback) {
        if (executionResultCallback != null) {
            executionResultCallback.executed(false, null, new Error("Cancelled by subsequent request"));
        }
        executionResultCallback = callback;

        String callbackScript = "function() { TurbolinksNative.executeResultCallback(_result); }";

        String newScript = script +
            "if (typeof(webView) !== 'undefined') {" +
                "webView.afterNextRepaint(" + callbackScript + ");" +
            "} else { " +
                "window.requestAnimationFrame(" + callbackScript  + ");" +
            "}";

        runJavascriptWithEvalResult(newScript, new TurbolinksJSExecutionResultCallback() {
            @Override
            public void executed(Boolean finished, @Nullable String result, @Nullable Error error) {
                // handle error case
                if (error != null) {
                    TurbolinksJSExecutionResultCallback currentCallback = executionResultCallback;
                    executionResultCallback = null;
                    currentCallback.executed(false, null, error);
                }
            }
        });
    }


    // ---------------------------------------------------
    // Private
    // ---------------------------------------------------

    /**
     * <p>Adds the restoration (cached scroll position) identifier to the local Hashmap.</p>
     *
     * @param value Restoration ID provided by Turbolinks.
     */
    private void addRestorationIdentifierToMap(String value) {
        if (fragment != null) {
            restorationIdentifierMap.put(fragment.toString(), value);
        }
    }

    /**
     * <p>Gets the restoration ID for the current fragment.</p>
     *
     * @return Restoration ID for the current fragment.
     */
    private String getRestorationIdentifierFromMap() {
        return restorationIdentifierMap.get(fragment.toString());
    }

    /**
     * <p>Shows the progress view, either a custom one provided or the default.</p>
     *
     * <p>A default progress view is inflated if {@link #progressView} isn't called.
     * If already inflated, progress view is fully detached before being shown since it's reused.</p>
     */
    public void initProgressView() {
        // No custom progress view provided, use default
        if (progressView == null) {
            progressView = LayoutInflater.from(activity).inflate(R.layout.turbolinks_progress, turbolinksView, false);
            progressIndicator = progressView.findViewById(R.id.turbolinks_default_progress_indicator);
        }

        // A progress view can be reused, so ensure it's detached from its previous parent first
        if (progressView.getParent() != null) {
            ((ViewGroup) progressView.getParent()).removeView(progressView);
        }

        // based on screenshot set transparent color
        if (turbolinksView.hasValidScreenshotView()) {
            progressView.setBackgroundColor(Color.TRANSPARENT);
            progressIndicator.setBackgroundColor(Color.TRANSPARENT);
            progressIndicatorDelay = PROGRESS_INDICATOR_DELAY;
        } else {
            Drawable background = turbolinksView.getBackground() != null ? turbolinksView.getBackground() : new ColorDrawable(Color.WHITE);
            progressView.setBackground(background);
            progressIndicatorDelay = 0;
        }

        // Executed from here to account for progress indicator delay
        turbolinksView.showProgress(progressView, progressIndicator, progressIndicatorDelay);
    }

    /**
     * <p>Convenience method to simply revisit the current location in the TurbolinksSession. Useful
     * so that different visit logic can be wrappered around this call in {@link #visit} or
     * {@link #setTurbolinksIsReady(boolean)}</p>
     */
    private void visitCurrentLocationWithTurbolinks() {
        TurbolinksLog.d("Visiting current stored location: " + location);

        String action = restoreWithCachedSnapshot ? ACTION_RESTORE : ACTION_ADVANCE;
        visitLocationWithAction(location, action);
    }

    /**
     * <p>Ensures all required chained calls/parameters ({@link #activity}, {@link #turbolinksView},
     * and location}) are set before calling {@link #visit(String)}.</p>
     */
    private void validateRequiredParams() {
        if (activity == null) {
            throw new IllegalArgumentException("TurbolinksSession.activity(activity) must be called with a non-null object.");
        }

        if (fragment == null) {
            throw new IllegalArgumentException("TurbolinksSession.fragment(fragment) must be called with a non-null object.");
        }

        if (turbolinksAdapter == null) {
            throw new IllegalArgumentException("TurbolinksSession.adapter(turbolinksAdapter) must be called with a non-null object.");
        }

        if (turbolinksView == null) {
            throw new IllegalArgumentException("TurbolinksSession.view(turbolinksView) must be called with a non-null object.");
        }

        if (TextUtils.isEmpty(location)) {
            throw new IllegalArgumentException("TurbolinksSession.visit(location) location value must not be null.");
        }
    }

    // ---------------------------------------------------
    // Interfaces
    // ---------------------------------------------------

    @Override
    public void onSwipeRefreshLayoutSizeChanged(int w, int h, int oldw, int oldh) {
        if ((webView != null) && ((webView.getHeight() != h) || (webView.getWidth() != w))) {
            refreshWebViewLayout();
        }
    }

    public void refreshWebViewLayout() {
        final WebView localWebView = webView;
        TurbolinksHelper.runOnMainThread(activity, new Runnable() {
            @Override
            public void run() {
                localWebView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
        });
    }

    public void setRefreshIndicatorOffsetY(int offsetY) {
        if ((this.turbolinksView != null) && offsetY != this.turbolinksView.getRefreshLayout().getProgressViewStartOffset()) {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int newValue = (int) (offsetY + 100 * metrics.density);
            this.turbolinksView.getRefreshLayout().setProgressViewOffset(true, offsetY, (int) (offsetY + 100 * metrics.density));
        }
    }

    @Override
    public boolean canChildScrollUp(@NonNull SwipeRefreshLayout parent, @Nullable View child) {
        if (pullToRefreshEnabled && (this.webView != null) && (child != null)) {
            return (this.webView.canScrollVertically(-1));
//          return (this.webView.getScrollY() > 0);
        }
        return true; // we can scroll up => ignore pullToRefresh
    }
}


