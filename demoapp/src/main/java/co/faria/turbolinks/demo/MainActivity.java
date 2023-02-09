package co.faria.turbolinks.demo;

import android.content.Intent;
import android.os.Bundle;

import co.faria.turbolinks.ActivityResultListener;
import co.faria.turbolinks.ActivityResultListenerRegistry;
import co.faria.turbolinks.TurbolinksAdapter;
import co.faria.turbolinks.TurbolinksSession;
import co.faria.turbolinks.TurbolinksView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements ActivityResultListenerRegistry {
    // -----------------------------------------------------------------------
    // Activity overrides
    // -----------------------------------------------------------------------

    private ArrayList<ActivityResultListener> activityResultListener = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TurbolinksSession.getDefault(this) // create session
                .activity(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        for (ActivityResultListener item: activityResultListener) {
            item.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void registerActivityResultListener(ActivityResultListener listener) {
        activityResultListener.add(listener);
    }

    @Override
    public void unregisterActivityResultListener(ActivityResultListener listener) {
        activityResultListener.remove(listener);
    }
}
