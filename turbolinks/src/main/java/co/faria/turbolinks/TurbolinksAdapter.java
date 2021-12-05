package co.faria.turbolinks;

/**
 * <p>Defines callbacks that Turbolinks makes available to your app. This interface is required, and
 * should be implemented in an activity (or similar class).</p>
 *
 * <p>Often these callbacks handle error conditions, but there are also some convenient timing events
 * where you can do things like routing, inject custom Javascript, etc.</p>
 */
public interface TurbolinksAdapter {
    /**
     * Get current location from turbolinks session
     */
    String getCurrentLocation();

    /**
     * <p>Called after the Turbolinks Javascript bridge has been injected into the webView, during the
     * Android WebViewClient's standard onPageFinished callback.
     */
    void onPageFinished();

    /**
     * <p>Called when the Android WebViewClient's standard onReceivedError callback is fired.</p>
     *
     * @param message Passed through error message returned by the Android WebViewClient.
     * @param errorCode Passed through error code returned by the Android WebViewClient.
     */
    void onReceivedError(String message, int errorCode);

    /**
     * <p>Called when the Android WebViewClient's standard onReceivedError callback is fired.</p>
     *
     * @param httpErrorCode Passed through error code returned by the Android WebViewClient.
     */
    void onReceivedHttpError(String message, int httpErrorCode);

    /**
     * <p>Called when Turbolinks detects that the page being visited has been invalidated, typically
     * by new resources in the the page HEAD.</p>
     */
    void pageInvalidated();

    /**
     *<p>Called when Turbolinks receives an HTTP error from a Turbolinks request.</p>
     *
     * @param statusCode HTTP status code returned by the request.
     */
    void requestFailedWithStatusCode(int statusCode);

    /**
     * <p>Called when Turbolinks starts a visit </p>
     */

    void visitStarted(String location);

    /**
     * <p>Called when Turbolinks considers the visit fully completed -- the request fulfilled
     * successfully and page rendered.</p>
     */
    void visitCompleted(String visitIdentifier);

    /**
     * <p>Called when Turbolinks first starts a visit, typically from a link inside a webView.</p>
     *
     * @param location URL to be visited.
     * @param action Whether to treat the request as an advance (navigating forward) or a replace (back).
     */
    void visitProposedToLocationWithAction(String location, String action);

    /**
     * <p>Called when WebView was unhidden.</p>
     */
    void webViewRendered(Runnable showAction);

    /**
     * <p>Called when WebView received a redirect.</p>
     */
    Boolean requestRedirect(String location);

}
