package co.faria.turbolinks.demo;

import android.content.Intent;
import android.os.Bundle;

import co.faria.turbolinks.TurbolinksAdapter;
import co.faria.turbolinks.TurbolinksSession;
import co.faria.turbolinks.TurbolinksView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    // -----------------------------------------------------------------------
    // Activity overrides
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TurbolinksSession.getDefault(this) // create session
                .activity(this);
    }
}
