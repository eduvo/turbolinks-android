package co.faria.turbolinks;

public interface TLFileChooserInterceptor {
    boolean handleShowFileChooser(TLChromeClientWithFileChooser client, String type, final boolean isCaptureEnabled, boolean multiSelect);
}
