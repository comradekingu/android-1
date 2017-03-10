/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.syncadapter;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;

import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.Constants;
import com.etesync.syncadapter.HttpClient;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.JournalEntryManager;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.SyncEntry;
import com.etesync.syncadapter.resource.LocalAddressBook;
import com.etesync.syncadapter.resource.LocalContact;
import com.etesync.syncadapter.resource.LocalGroup;
import com.etesync.syncadapter.resource.LocalResource;
import at.bitfire.ical4android.CalendarStorageException;
import at.bitfire.vcard4android.Contact;
import at.bitfire.vcard4android.ContactsStorageException;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * <p>Synchronization manager for CardDAV collections; handles contacts and groups.</p>
 */
public class ContactsSyncManager extends SyncManager {
    protected static final int MAX_MULTIGET = 10;

    final private ContentProviderClient provider;
    final private HttpUrl remote;
    final private CollectionInfo info;

    public ContactsSyncManager(Context context, Account account, AccountSettings settings, Bundle extras, String authority, ContentProviderClient provider, SyncResult result, HttpUrl principal, CollectionInfo info) throws InvalidAccountException {
        super(context, account, settings, extras, authority, result, "addressBook", CollectionInfo.Type.ADDRESS_BOOK);
        this.provider = provider;
        this.remote = principal;
        this.info = info;
    }

    @Override
    protected int notificationId() {
        return Constants.NOTIFICATION_CONTACTS_SYNC;
    }

    @Override
    protected String getSyncErrorTitle() {
        return context.getString(R.string.sync_error_contacts, account.name);
    }

    @Override
    protected boolean prepare() throws ContactsStorageException, CalendarStorageException {
        if (!super.prepare())
            return false;
        // prepare local address book
        localCollection = new LocalAddressBook(account, provider);
        LocalAddressBook localAddressBook = localAddressBook();
        localAddressBook.setURL(info.url);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // workaround for Android 7 which sets DIRTY flag when only meta-data is changed
            int reallyDirty = localAddressBook.verifyDirty(),
                deleted = localAddressBook.getDeleted().length;
            if (extras.containsKey(ContentResolver.SYNC_EXTRAS_UPLOAD) && reallyDirty == 0 && deleted == 0) {
                App.log.info("This sync was called to up-sync dirty/deleted contacts, but no contacts have been changed");
                return false;
            }
        }

        // set up Contacts Provider Settings
        ContentValues values = new ContentValues(2);
        values.put(ContactsContract.Settings.SHOULD_SYNC, 1);
        values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
        localAddressBook.updateSettings(values);

        journal = new JournalEntryManager(httpClient, remote, info.url);

        return true;
    }

    @Override
    protected void prepareDirty() throws CalendarStorageException, ContactsStorageException {
        super.prepareDirty();

        LocalAddressBook addressBook = localAddressBook();

        /* groups as separate VCards: thtere are group contacts and individual contacts */

        // mark groups with changed members as dirty

        // FIXME: add back

    }

    @Override
    protected void postProcess() throws CalendarStorageException, ContactsStorageException {
        super.postProcess();
        /* VCard4 group handling: there are group contacts and individual contacts */
        App.log.info("Assigning memberships of downloaded contact groups");
        LocalGroup.applyPendingMemberships(localAddressBook());
    }


    // helpers

    private LocalAddressBook localAddressBook() {
        return (LocalAddressBook) localCollection;
    }

    protected void processSyncEntry(SyncEntry cEntry) throws IOException, ContactsStorageException, CalendarStorageException {
        InputStream is = new ByteArrayInputStream(cEntry.getContent().getBytes(Charsets.UTF_8));
        Contact.Downloader downloader = new ResourceDownloader();

        Contact[] contacts = Contact.fromStream(is, Charsets.UTF_8, downloader);
        if (contacts.length == 0) {
            App.log.warning("Received VCard without data, ignoring");
            return;
        } else if (contacts.length > 1)
            App.log.warning("Received multiple VCards, using first one");

        Contact contact = contacts[0];
        LocalResource local = (LocalResource) localCollection.getByUid(contact.uid);


        if (cEntry.isAction(SyncEntry.Actions.ADD) || cEntry.isAction(SyncEntry.Actions.CHANGE)) {
            processContact(contact, local);
        } else {
            App.log.info("Removing local record #" + local.getId() + " which has been deleted on the server");
            local.delete();
        }
    }

    private LocalResource processContact(final Contact newData, LocalResource local) throws IOException, ContactsStorageException {
        String uuid = newData.uid;
        // update local contact, if it exists
        if (local != null) {
            App.log.log(Level.INFO, "Updating " + uuid + " in local address book", newData);

            if (local instanceof LocalGroup && newData.group) {
                // update group
                LocalGroup group = (LocalGroup) local;
                group.eTag = uuid;
                group.updateFromServer(newData);
                syncResult.stats.numUpdates++;

            } else if (local instanceof LocalContact && !newData.group) {
                // update contact
                LocalContact contact = (LocalContact) local;
                contact.eTag = uuid;
                contact.update(newData);
                syncResult.stats.numUpdates++;

            } else {
                // group has become an individual contact or vice versa
                try {
                    local.delete();
                    local = null;
                } catch (CalendarStorageException e) {
                    // CalendarStorageException is not used by LocalGroup and LocalContact
                }
            }
        }

        if (local == null) {
            if (newData.group) {
                App.log.log(Level.INFO, "Creating local group", newData);
                LocalGroup group = new LocalGroup(localAddressBook(), newData, uuid, null);
                group.create();

                local = group;
            } else {
                App.log.log(Level.INFO, "Creating local contact", newData);
                LocalContact contact = new LocalContact(localAddressBook(), newData, uuid, null);
                contact.create();

                local = contact;
            }
            syncResult.stats.numInserts++;
        }

        return local;
    }

    // downloader helper class

    @RequiredArgsConstructor
    private class ResourceDownloader implements Contact.Downloader {
        @Override
        public byte[] download(String url, String accepts) {
            HttpUrl httpUrl = HttpUrl.parse(url);

            if (httpUrl == null) {
                App.log.log(Level.SEVERE, "Invalid external resource URL", url);
                return null;
            }

            String host = httpUrl.host();
            if (host == null) {
                App.log.log(Level.SEVERE, "External resource URL doesn't specify a host name", url);
                return null;
            }

            OkHttpClient resourceClient = HttpClient.create(context);

            // authenticate only against a certain host, and only upon request
            // resourceClient = HttpClient.addAuthentication(resourceClient, baseUrl.host(), settings.username(), settings.password());

            // allow redirects
            resourceClient = resourceClient.newBuilder()
                    .followRedirects(true)
                    .build();

            try {
                Response response = resourceClient.newCall(new Request.Builder()
                        .get()
                        .url(httpUrl)
                        .build()).execute();

                ResponseBody body = response.body();
                if (body != null) {
                    @Cleanup InputStream stream = body.byteStream();
                    if (response.isSuccessful() && stream != null) {
                        return IOUtils.toByteArray(stream);
                    } else
                        App.log.severe("Couldn't download external resource");
                }
            } catch (IOException e) {
                App.log.log(Level.SEVERE, "Couldn't download external resource", e);
            }
            return null;
        }
    }

}