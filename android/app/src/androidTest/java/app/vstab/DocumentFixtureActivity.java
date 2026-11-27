package app.vstab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Test APK only. Grants the production app access to synthetic fixture documents. */
public final class DocumentFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri root = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        getContentResolver().call(root, "seed", "demo", null);
        grantUriPermission("app.vstab", root, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
