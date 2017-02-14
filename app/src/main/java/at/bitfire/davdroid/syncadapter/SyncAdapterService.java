/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.syncadapter;

import android.accounts.Account;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import at.bitfire.davdroid.AccountSettings;
import at.bitfire.davdroid.App;
import at.bitfire.davdroid.Constants;
import at.bitfire.davdroid.HttpClient;
import at.bitfire.davdroid.InvalidAccountException;
import at.bitfire.davdroid.R;
import at.bitfire.davdroid.journalmanager.Exceptions;
import at.bitfire.davdroid.journalmanager.JournalManager;
import at.bitfire.davdroid.model.CollectionInfo;
import at.bitfire.davdroid.model.ServiceDB;
import at.bitfire.davdroid.ui.PermissionsActivity;
import lombok.Cleanup;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

//import com.android.vending.billing.IInAppBillingService;

public abstract class SyncAdapterService extends Service {

    abstract protected AbstractThreadedSyncAdapter syncAdapter();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return syncAdapter().getSyncAdapterBinder();
    }


    public static abstract class SyncAdapter extends AbstractThreadedSyncAdapter {

        public SyncAdapter(Context context) {
            super(context, false);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
            App.log.log(Level.INFO, "Sync for " + authority + " has been initiated.", extras.keySet().toArray());

            // required for dav4android (ServiceLoader)
            Thread.currentThread().setContextClassLoader(getContext().getClassLoader());
        }

        @Override
        public void onSecurityException(Account account, Bundle extras, String authority, SyncResult syncResult) {
            App.log.log(Level.WARNING, "Security exception when opening content provider for " + authority);
            syncResult.databaseError = true;

            Intent intent = new Intent(getContext(), PermissionsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Notification notify = new NotificationCompat.Builder(getContext())
                    .setSmallIcon(R.drawable.ic_error_light)
                    .setLargeIcon(App.getLauncherBitmap(getContext()))
                    .setContentTitle(getContext().getString(R.string.sync_error_permissions))
                    .setContentText(getContext().getString(R.string.sync_error_permissions_text))
                    .setContentIntent(PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build();
            NotificationManagerCompat nm = NotificationManagerCompat.from(getContext());
            nm.notify(Constants.NOTIFICATION_PERMISSIONS, notify);
        }

        protected boolean checkSyncConditions(@NonNull AccountSettings settings) {
            if (settings.getSyncWifiOnly()) {
                ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo network = cm.getActiveNetworkInfo();
                if (network == null) {
                    App.log.info("No network available, stopping");
                    return false;
                }
                if (network.getType() != ConnectivityManager.TYPE_WIFI || !network.isConnected()) {
                    App.log.info("Not on connected WiFi, stopping");
                    return false;
                }

                String onlySSID = settings.getSyncWifiOnlySSID();
                if (onlySSID != null) {
                    onlySSID = "\"" + onlySSID + "\"";
                    WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info = wifi.getConnectionInfo();
                    if (info == null || !onlySSID.equals(info.getSSID())) {
                        App.log.info("Connected to wrong WiFi network (" + info.getSSID() + ", required: " + onlySSID + "), ignoring");
                        return false;
                    }
                }
            }
            return true;
        }

        protected class RefreshCollections {
            final private ServiceDB.OpenHelper dbHelper;
            final private Account account;
            final private Context context;
            final private CollectionInfo.Type serviceType;

            RefreshCollections(Account account, CollectionInfo.Type serviceType) {
                this.account = account;
                this.serviceType = serviceType;
                context = getContext();
                dbHelper = new ServiceDB.OpenHelper(context);
            }

            void run() throws Exceptions.HttpException, Exceptions.IntegrityException, InvalidAccountException {
                try {
                    @Cleanup SQLiteDatabase db = dbHelper.getWritableDatabase();

                    App.log.info("Refreshing " + serviceType + " collections of service #" + serviceType.toString());

                    OkHttpClient httpClient = HttpClient.create(context, account);

                    AccountSettings settings = new AccountSettings(context, account);
                    JournalManager journalsManager = new JournalManager(httpClient, HttpUrl.get(settings.getUri()));

                    List<CollectionInfo> collections = new LinkedList<>();

                    for (JournalManager.Journal journal : journalsManager.getJournals(settings.password())) {
                        CollectionInfo info = CollectionInfo.fromJson(journal.getContent(settings.password()));
                        info.url = journal.getUuid();
                        if (info.type.equals(serviceType)) {
                            collections.add(info);
                        }
                    }

                    // FIXME: handle deletion from server

                    if (collections.isEmpty()) {
                        CollectionInfo info = CollectionInfo.defaultForServiceType(serviceType);
                        JournalManager.Journal journal = new JournalManager.Journal(settings.password(), info.toJson());
                        journalsManager.putJournal(journal);
                        info.url = journal.getUuid();
                        collections.add(info);
                    }

                    db.beginTransactionNonExclusive();
                    try {
                        saveCollections(db, collections);
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                } finally {
                    dbHelper.close();
                }
            }

            @NonNull
            private Map<String, CollectionInfo> readCollections(SQLiteDatabase db) {
                Long service = dbHelper.getService(db, account, serviceType.toString());
                Map<String, CollectionInfo> collections = new LinkedHashMap<>();
                @Cleanup Cursor cursor = db.query(ServiceDB.Collections._TABLE, null, ServiceDB.Collections.SERVICE_ID + "=?", new String[]{String.valueOf(service)}, null, null, null);
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cursor, values);
                    collections.put(values.getAsString(ServiceDB.Collections.URL), CollectionInfo.fromDB(values));
                }
                return collections;
            }

            private void saveCollections(SQLiteDatabase db, Iterable<CollectionInfo> collections) {
                Long service = dbHelper.getService(db, account, serviceType.toString());
                db.delete(ServiceDB.Collections._TABLE, ServiceDB.Collections.SERVICE_ID + "=?", new String[]{String.valueOf(service)});
                for (CollectionInfo collection : collections) {
                    ContentValues values = collection.toDB();
                    App.log.log(Level.FINE, "Saving collection", values);
                    values.put(ServiceDB.Collections.SERVICE_ID, service);
                    db.insertWithOnConflict(ServiceDB.Collections._TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
        }
    }
}
