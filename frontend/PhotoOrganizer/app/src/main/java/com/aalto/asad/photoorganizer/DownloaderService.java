package com.aalto.asad.photoorganizer;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class DownloaderService extends IntentService {
    // TODO: Rename actions, choose action names that describe tasks that this
    // IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS

    private static final String TAG = "MCC";
    private static final int TYPE_WIFI = 100;
    private static final int TYPE_MOBILE_DATA = 101;
    private static final int TYPE_NO_NETWORK = 102;

    private static String groupID;
    private static String downloadQuality;
    private static AppDatabase appDatabase;
    private static DatabaseReference mPicturesReference;
    private static StorageReference mStorageReference;
    private static DatabaseReference mUsersReference;
    private static ChildEventListener mPicEventListener;

    private String picFull;
    private String picHigh;
    private String picLow;
    private String userName;

    public DownloaderService() {
        super("DownloaderService");
    }

    /**
     * Starts this service to perform action Foo with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    // TODO: Customize helper method
    public static void syncGroup(Context context, String group) {

        Intent intent = new Intent(context, DownloaderService.class);
        intent.setAction("SYNC_GROUP");
        intent.putExtra("Group", group);
        context.startService(intent);
    }

    /**
     * Starts this service to perform action Baz with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    // TODO: Customize helper method --download a specific pic?
    /*
    public static void startActionBaz(Context context, String param1, String param2) {
        Intent intent = new Intent(context, DownloaderService.class);
        intent.setAction(ACTION_BAZ);
        intent.putExtra(EXTRA_PARAM1, param1);
        intent.putExtra(EXTRA_PARAM2, param2);
        context.startService(intent);
    }*/

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if ("SYNC_GROUP".equals(action)) {
                Log.d(TAG, "Sync group action of the service intitated.");
                final String group = intent.getStringExtra("Group");
                handleSyncGroup(group);
            } /** else if (ACTION_BAZ.equals(action)) {
                final String param1 = intent.getStringExtra(EXTRA_PARAM1);
                final String param2 = intent.getStringExtra(EXTRA_PARAM2);
                handleActionBaz(param1, param2);
            } */
        }
    }

    /**
     * Handle action Foo in the provided background thread with the provided
     * parameters.
     */
    private void handleSyncGroup(String group) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        appDatabase = AppDatabase.getInstance(getApplicationContext());
        if(!group.equals(groupID)) {
            Log.d(TAG, "Group changed, resetting listener");
            Log.d(TAG, "groupID in service is: "+groupID);
            Log.d(TAG, "group received from call is: "+group);
            if (mPicEventListener != null ) {
                mPicturesReference.removeEventListener(mPicEventListener);
            }
        } else {
            if (mPicEventListener != null) {
                Log.d(TAG, "Returning, listener already running");
                return;
            } else {
                Log.d(TAG, "Listener not yet running");
            }
        }
        groupID = group;
        mStorageReference = FirebaseStorage.getInstance().getReference();

        mPicturesReference = FirebaseDatabase.getInstance().getReference().child("groups").child(groupID).child("pictures");
        mPicEventListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String prevChildKey) {
                //Log.i("MCC", "downloadImages:Group id " + groupID);
                if (!groupID.isEmpty()) {
                    int networkStatus = checkNetworkStatus(getApplicationContext());
                    downloadQuality = determineDownloadQuality(networkStatus);
                    final PicturesGroup picturesGroup = new PicturesGroup();
                    picturesGroup.setContains_people(dataSnapshot.getValue(PicturesGroup.class).getContains_people());
                    picturesGroup.setPicture(dataSnapshot.getValue(PicturesGroup.class).getPicture());
                    picturesGroup.setPicture_640(dataSnapshot.getValue(PicturesGroup.class).getPicture_640());
                    picturesGroup.setPicture_1280(dataSnapshot.getValue(PicturesGroup.class).getPicture_1280());
                    picturesGroup.setUser_id(dataSnapshot.getValue(PicturesGroup.class).getUser_id());

                    String containsPeople = picturesGroup.getContains_people().toString();
                    //Log.i("MCC", "onChildAdded " + picturesGroup.getUser_id());

                    mStorageReference.child(picturesGroup.getPicture_640()).getDownloadUrl().addOnSuccessListener(new OnSuccessListener<Uri>() {
                        @Override
                        public void onSuccess(Uri uri) {
                            picLow = uri.toString();       // Low pic
                            Log.i("MCC", "onChildAdded (dlq is "+downloadQuality+") " + picLow);
                            if (downloadQuality.equals("Low")) {
                                StorageReference pictureRef = mStorageReference.child(uri.toString());
                                //try {
                                    File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES+"/"+groupID+"/");
                                    if(!storageDir.exists()) {
                                        storageDir.mkdirs();
                                    }
                                    Log.d(TAG, "storageDir: "+storageDir.getPath());
                                    File localFile = new File(storageDir, "tmp"+Long.toString(Calendar.getInstance().getTimeInMillis())+".jpg");
                                    DownloadTask downloadTask = new DownloadTask();
                                    downloadTask.execute(new DLparams(picLow, localFile));
                                    //localFile.createNewFile();
                                    /** localFile = File.createTempFile(
                                            uri.toString(),
                                            ".jpg",
                                            storageDir
                                    );*/
                                    //FileUtils.copyURLToFile(new java.net.URL(picLow), localFile);
                                    /**pictureRef.getFile(localFile).addOnSuccessListener(new OnSuccessListener<FileDownloadTask.TaskSnapshot>() {
                                        @Override
                                        public void onSuccess(FileDownloadTask.TaskSnapshot taskSnapshot) {
                                            picturesGroup.setLocalUri(localFile.getPath());
                                        }
                                    }).addOnFailureListener(new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            Log.d(TAG, "Unable to download image file.");
                                        }
                                    })*/
                                //} catch (IOException e) {
                                //    Log.d(TAG, "Unable to create file: "+e.getLocalizedMessage());
                                //}
                            }
                        }
                    });

                    mStorageReference.child(picturesGroup.getPicture_1280()).getDownloadUrl().addOnSuccessListener(new OnSuccessListener<Uri>() {
                        @Override
                        public void onSuccess(Uri uri) {
                            picHigh = uri.toString();       // High pic
                            Log.i("MCC", "onChildAdded (dlq is "+downloadQuality+") " + picHigh);
                            if (downloadQuality.equals("High")) {
                                StorageReference pictureRef = mStorageReference.child(uri.toString());
                                //try {
                                File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES+"/"+groupID+"/");
                                if(!storageDir.exists()) {
                                    storageDir.mkdirs();
                                }
                                Log.d(TAG, "storageDir: "+storageDir.getPath());
                                File localFile = new File(storageDir, "tmp"+Long.toString(Calendar.getInstance().getTimeInMillis())+".jpg");
                                DownloadTask downloadTask = new DownloadTask();
                                downloadTask.execute(new DLparams(picLow, localFile));
                                //localFile.createNewFile();
                                /** localFile = File.createTempFile(
                                 uri.toString(),
                                 ".jpg",
                                 storageDir
                                 );*/
                                //FileUtils.copyURLToFile(new java.net.URL(picLow), localFile);
                                /**pictureRef.getFile(localFile).addOnSuccessListener(new OnSuccessListener<FileDownloadTask.TaskSnapshot>() {
                                @Override
                                public void onSuccess(FileDownloadTask.TaskSnapshot taskSnapshot) {
                                picturesGroup.setLocalUri(localFile.getPath());
                                }
                                }).addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                Log.d(TAG, "Unable to download image file.");
                                }
                                })*/
                                //} catch (IOException e) {
                                //    Log.d(TAG, "Unable to create file: "+e.getLocalizedMessage());
                                //}
                            }
                        }
                    });

                    mStorageReference.child(picturesGroup.getPicture()).getDownloadUrl().addOnSuccessListener(new OnSuccessListener<Uri>() {
                        @Override
                        public void onSuccess(Uri uri) {
                            picFull = uri.toString();       // Full
                            Log.i("MCC", "onChildAdded (dlq is "+downloadQuality+") " + picFull);
                            if (downloadQuality.equals("Full")) {
                                StorageReference pictureRef = mStorageReference.child(uri.toString());
                                //try {
                                File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES+"/"+groupID+"/");
                                if(!storageDir.exists()) {
                                    storageDir.mkdirs();
                                }
                                Log.d(TAG, "storageDir: "+storageDir.getPath());
                                File localFile = new File(storageDir, "tmp"+Long.toString(Calendar.getInstance().getTimeInMillis())+".jpg");
                                DownloadTask downloadTask = new DownloadTask();
                                downloadTask.execute(new DLparams(picLow, localFile));
                                //localFile.createNewFile();
                                /** localFile = File.createTempFile(
                                 uri.toString(),
                                 ".jpg",
                                 storageDir
                                 );*/
                                //FileUtils.copyURLToFile(new java.net.URL(picLow), localFile);
                                /**pictureRef.getFile(localFile).addOnSuccessListener(new OnSuccessListener<FileDownloadTask.TaskSnapshot>() {
                                @Override
                                public void onSuccess(FileDownloadTask.TaskSnapshot taskSnapshot) {
                                picturesGroup.setLocalUri(localFile.getPath());
                                }
                                }).addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                Log.d(TAG, "Unable to download image file.");
                                }
                                })*/
                                //} catch (IOException e) {
                                //    Log.d(TAG, "Unable to create file: "+e.getLocalizedMessage());
                                //}
                            }
                        }
                    });


                    mUsersReference = FirebaseDatabase.getInstance().getReference().child("users").child(picturesGroup.getUser_id());
                    mUsersReference.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            User user = dataSnapshot.getValue(User.class);
                            userName = user.getUserName();
                            Log.i("MCC", "user Name " + userName);
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {

                        }
                    });

//                        if (picLow != null && picHigh != null && picFull != null && userName != null) {
//                            Log.i("MCC", "before insertToRoomDatabase function");
//                            insertToRoomDatabase(groupID, containsPeople, picFull, picHigh, picLow, userName);
//                        }

                    Log.i("MCC", "before insertToRoomDatabase function");
                    readData(groupID);
                }
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String prevChildKey) {}

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {}

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String prevChildKey) {}

            @Override
            public void onCancelled(DatabaseError databaseError) {}
        };
        mPicturesReference.addChildEventListener(mPicEventListener);
    }

    public void insertToRoomDatabase(final String groupID, final String containsPeople, final String picFull, final String picHigh, final String picLow, final String userName) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                PictureInfo pictureInfo = new PictureInfo();
                pictureInfo.setGroupId(groupID);
                pictureInfo.setContainsPeople(containsPeople);
                pictureInfo.setPictureFull(picFull);
                pictureInfo.setPictureHigh(picHigh);
                pictureInfo.setPictureLow(picLow);
                pictureInfo.setUserName(userName);
                appDatabase.pictureInfoDao().insertOnlySingleRecord(pictureInfo);
                Log.d("MCC", "Data added to the database");
                return null;
            }
        }.execute();
    }


    // For reading
    public void readData(final String groupID) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                List<PictureInfo> pictureInfoList = appDatabase.pictureInfoDao().getAll();
                for (int i = 0; i < pictureInfoList.size();i++) {
                    PictureInfo p = pictureInfoList.get(i);
                    Log.i("MCC", "in Read: room database " + p.getPictureFull());
                }
                return null;
            }
        }.execute();
    }

    /**
     * Handle action Baz in the provided background thread with the provided
     * parameters.
     */
    /**private void handleActionBaz(String param1, String param2) {
        // TODO: Handle action Baz
        throw new UnsupportedOperationException("Not yet implemented");
    }*/

    public int checkNetworkStatus(Context mContext) {

        int networkStatus;
        final ConnectivityManager manager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert manager != null;
        final android.net.NetworkInfo wifi = manager.getActiveNetworkInfo();
        final android.net.NetworkInfo mobile = manager.getActiveNetworkInfo();

        if( wifi.getType() == ConnectivityManager.TYPE_WIFI) {
            networkStatus = TYPE_WIFI;
        }else if(mobile.getType() == ConnectivityManager.TYPE_MOBILE){
            networkStatus = TYPE_MOBILE_DATA;
        }else{
            networkStatus = TYPE_NO_NETWORK;
        }
        return networkStatus;
    }

    public String determineDownloadQuality (int networkStatus) {
        SharedPreferences sharedpref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Log.d(TAG, "Determining download quality, network status is: "+networkStatus+" ");
        if (networkStatus == TYPE_WIFI) {
            return sharedpref.getString("pref_key_wifi_sync", "");
        } else if (networkStatus == TYPE_MOBILE_DATA) {
            return sharedpref.getString("pref_key_mdata_sync", "");
        } else {
            return "";
        }
    }
}