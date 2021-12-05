package co.faria.turbolinks;

import androidx.annotation.Nullable;

/**
 * <p>Defines a callback for determining whether or not a child view can scroll up.</p>
 */
public interface TurbolinksJSExecutionResultCallback {
    void executed(Boolean finished, @Nullable String result, @Nullable Error error);
}
