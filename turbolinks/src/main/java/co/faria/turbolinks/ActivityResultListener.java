package co.faria.turbolinks;

import android.content.Intent;

public interface ActivityResultListener {
    int activityResultType();
    void onActivityResult(int requestCode, int resultCode, Intent data);
}

