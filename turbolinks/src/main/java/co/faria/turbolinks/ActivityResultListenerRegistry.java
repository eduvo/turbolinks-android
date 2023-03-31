package co.faria.turbolinks;

public interface ActivityResultListenerRegistry {
    void registerActivityResultListener(ActivityResultListener listener);

    void unregisterActivityResultListener(ActivityResultListener listener);
}
