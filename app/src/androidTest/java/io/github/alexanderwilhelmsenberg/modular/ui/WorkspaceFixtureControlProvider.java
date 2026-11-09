package io.github.alexanderwilhelmsenberg.modular.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** IPC state shared with the provider configuration Activity in the test APK process. */
public class WorkspaceFixtureControlProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Fixture provider has no context");
        SharedPreferences preferences = context.getSharedPreferences("workspace_fixture_config", 0);
        if ("snapshot".equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("accept_next", preferences.getBoolean("accept_next", true));
            result.putInt("launches", preferences.getInt("launches", 0));
            result.putInt("last_id", preferences.getInt("last_id", -1));
            return result;
        }
        if ("set_accept".equals(method)) {
            if (extras == null || !extras.containsKey("accept_next"))
                throw new IllegalArgumentException("Missing accept_next");
            if (!preferences.edit().putBoolean("accept_next", extras.getBoolean("accept_next")).commit())
                throw new IllegalStateException("Could not save fixture choice");
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("Unknown fixture method: " + method);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) { return null; }
    @Override
    public String getType(Uri uri) { return null; }
    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) { return 0; }
}
