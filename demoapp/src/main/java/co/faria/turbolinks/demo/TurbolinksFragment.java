package co.faria.turbolinks.demo;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import co.faria.turbolinks.TurbolinksAdapter;
import co.faria.turbolinks.TurbolinksSession;
import co.faria.turbolinks.TurbolinksView;

public class TurbolinksFragment extends Fragment implements TurbolinksAdapter {
    // Change the BASE_URL to an address that your VM or device can hit.
    private static final String BASE_URL = "https://faria.managebac.com/";
    private static final String INTENT_URL = "intentUrl";

    private String location;
    private TurbolinksView turbolinksView;

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.turbolinks_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find the custom TurbolinksView object in your layout
        turbolinksView = (TurbolinksView) view.findViewById(R.id.turbolinks_view);

        // For this demo app, we force debug logging on. You will only want to do
        // this for debug builds of your app (it is off by default)
        TurbolinksSession.getDefault(getContext()).setDebugLoggingEnabled(true);

        // For this example we set a default location, unless one is passed in through an intent
        location = requireActivity().getIntent().getStringExtra(INTENT_URL) != null ? requireActivity().getIntent().getStringExtra(INTENT_URL) : BASE_URL;

        TurbolinksSession.getDefault(requireActivity())
                .adapter(this)
                .fragment(this)
                .view(turbolinksView)
                .visit(location);
    }

    // -----------------------------------------------------------------------
    // TurbolinksAdapter interface
    // -----------------------------------------------------------------------


    @Override
    public void onReceivedError(String message, int errorCode) {
        // ignore
    }

    @Override
    public void onReceivedHttpError(String message, int httpErrorCode) {

    }

    @Override
    public String getCurrentLocation() {
        return "";
    }

    @Override
    public void visitStarted(String location) {

    }

    @Override
    public void visitCompleted(String visitIdentifier) {
        Log.d("Demo", "Visit completed");
    }

    @Override
    public void webViewRendered(Runnable showAction) {
        showAction.run();
    }

    @Override
    public Boolean requestRedirect(String location) {
        return true;
    }

    @Override
    public void onPageFinished() {

    }

    public void onReceivedError(int errorCode) {
        handleError(errorCode);
    }

    @Override
    public void pageInvalidated() {

    }

    @Override
    public void requestFailedWithStatusCode(int statusCode) {
        handleError(statusCode);
    }

    public void visitCompleted() {
    }

    // The starting point for any href clicked inside a Turbolinks enabled site. In a simple case
    // you can just open another activity, or in more complex cases, this would be a good spot for
    // routing logic to take you to the right place within your app.
    @Override
    public void visitProposedToLocationWithAction(String location, String action) {
        Intent intent = new Intent(requireActivity(), MainActivity.class);
        intent.putExtra(INTENT_URL, location);

        this.startActivity(intent);
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    // Simply forwards to an error page, but you could alternatively show your own native screen
    // or do whatever other kind of error handling you want.
    private void handleError(int code) {
        if (code == 404) {
            TurbolinksSession.getDefault(requireActivity())
                    .activity(requireActivity())
                    .adapter(this)
                    .restoreWithCachedSnapshot(false)
                    .fragment(this)
                    .view(turbolinksView)
                    .visit(BASE_URL + "/error");
        }
    }
}
