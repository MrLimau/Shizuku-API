package rikka.shizuku;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_REPLY_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_REPLY_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_REPLY_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_REPLY_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;

public class Shizuku {

    private static IBinder binder;
    private static IShizukuService service;

    private static int serverUid = -1;
    private static int serverVersion = -1;
    private static String serverContext = null;
    private static boolean permissionGranted = false;
    private static boolean shouldShowRequestPermissionRationale = false;
    private static boolean preV11 = false;

    private static final IShizukuApplication SHIZUKU_APPLICATION = new IShizukuApplication.Stub() {

        @Override
        public void bindApplication(Bundle data) {
            serverUid = data.getInt(ATTACH_REPLY_SERVER_UID, -1);
            serverVersion = data.getInt(ATTACH_REPLY_SERVER_VERSION, -1);
            serverContext = data.getString(ATTACH_REPLY_SERVER_SECONTEXT);
            permissionGranted = data.getBoolean(ATTACH_REPLY_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale = data.getBoolean(ATTACH_REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false);
            scheduleRequestPermissionResultListener(requestCode, allowed ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED);
        }

        @Override
        public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            // non-app
        }
    };

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> onBinderReceived(null, null);

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void onBinderReceived(@Nullable IBinder newBinder, String packageName) {
        if (binder == newBinder) return;

        if (newBinder == null) {
            binder = null;
            service = null;
            serverUid = -1;
            serverVersion = -1;
            serverContext = null;
        } else {
            if (binder != null) {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            }
            binder = newBinder;
            service = IShizukuService.Stub.asInterface(newBinder);

            try {
                binder.linkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable e) {
                Log.i("ShizukuApplication", "attachApplication");
            }

            try {
                //service.attachApplication(SHIZUKU_APPLICATION, packageName);

                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("moe.shizuku.server.IShizukuService");
                    data.writeStrongBinder(SHIZUKU_APPLICATION.asBinder());
                    data.writeString(packageName);
                    preV11 = !binder.transact(14 /*IShizukuService.Stub.TRANSACTION_attachApplication*/, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }

                Log.i("ShizukuApplication", "attachApplication");
            } catch (Throwable e) {
                Log.w("ShizukuApplication", Log.getStackTraceString(e));
            }

            scheduleBinderReceivedListeners();
        }
    }

    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    private static final List<OnBinderReceivedListener> RECEIVED_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<OnBinderDeadListener> DEAD_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<OnRequestPermissionResultListener> PERMISSION_LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Add a listener that will be called when binder is received.
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * <li>The listener could be called multiply times. For example, user restarts Shizuku when app is running.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(Objects.requireNonNull(listener), false);
    }

    /**
     * Same to {@link #addBinderReceivedListener(OnBinderReceivedListener)} but only call the listener
     * immediately if the binder is already received.
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderReceivedListenerSticky(@NonNull OnBinderReceivedListener listener) {
        addBinderReceivedListener(Objects.requireNonNull(listener), true);
    }

    private static void addBinderReceivedListener(@NonNull OnBinderReceivedListener listener, boolean sticky) {
        if (sticky && pingBinder()) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived();
            } else {
                MAIN_HANDLER.post(listener::onBinderReceived);
            }
        }
        RECEIVED_LISTENERS.add(listener);
    }

    /**
     * Remove the listener added by {@link #addBinderReceivedListener(OnBinderReceivedListener)}
     * or {@link #addBinderReceivedListenerSticky(OnBinderReceivedListener)}.
     *
     * @param listener OnBinderReceivedListener
     * @return If the listener is removed.
     */
    public static boolean removeBinderReceivedListener(@NonNull OnBinderReceivedListener listener) {
        return RECEIVED_LISTENERS.remove(listener);
    }

    private static void scheduleBinderReceivedListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchBinderReceivedListeners();
        } else {
            MAIN_HANDLER.post(Shizuku::dispatchBinderReceivedListeners);
        }
    }

    private static void dispatchBinderReceivedListeners() {
        for (OnBinderReceivedListener listener : RECEIVED_LISTENERS) {
            listener.onBinderReceived();
        }
    }

    /**
     * Add a listener that will be called when binder is dead.
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        DEAD_LISTENERS.add(listener);
    }

    /**
     * Remove the listener added by {@link #addBinderDeadListener(OnBinderDeadListener)}.
     *
     * @param listener OnBinderDeadListener
     * @return If the listener is removed.
     */
    public static boolean removeBinderDeadListener(@NonNull OnBinderDeadListener listener) {
        return DEAD_LISTENERS.remove(listener);
    }

    private static void scheduleBinderDeadListeners() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchBinderDeadListeners();
        } else {
            MAIN_HANDLER.post(Shizuku::dispatchBinderDeadListeners);
        }
    }

    private static void dispatchBinderDeadListeners() {
        for (OnBinderDeadListener listener : DEAD_LISTENERS) {
            listener.onBinderDead();
        }
    }

    /**
     * Add a listener that will be called when permission result is sent from server.
     * <p>Note:</p>
     * <ul>
     * <li>The listener will be called in main thread.</li>
     * </ul>
     * <p>
     *
     * @param listener OnBinderReceivedListener
     */
    public static void addRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        PERMISSION_LISTENERS.add(listener);
    }

    /**
     * Remove the listener added by {@link #addRequestPermissionResultListener(OnRequestPermissionResultListener)}.
     *
     * @param listener OnRequestPermissionResultListener
     * @return If the listener is removed.
     */
    public static boolean removeRequestPermissionResultListener(@NonNull OnRequestPermissionResultListener listener) {
        return PERMISSION_LISTENERS.remove(listener);
    }

    static void scheduleRequestPermissionResultListener(int requestCode, int result) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchRequestPermissionResultListener(requestCode, result);
        } else {
            MAIN_HANDLER.post(() -> dispatchRequestPermissionResultListener(requestCode, result));
        }
    }

    static void dispatchRequestPermissionResultListener(int requestCode, int result) {
        for (OnRequestPermissionResultListener listener : PERMISSION_LISTENERS) {
            listener.onRequestPermissionResult(requestCode, result);
        }
    }

    @NonNull
    protected static IShizukuService requireService() {
        if (service == null) {
            throw new IllegalStateException("binder haven't been received");
        }
        return service;
    }

    @Nullable
    public static IBinder getBinder() {
        return binder;
    }

    public static boolean pingBinder() {
        return binder != null && binder.pingBinder();
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }

    /**
     * Call {@link IBinder#transact(int, Parcel, Parcel, int)} at remote service.
     *
     * <p>How to construct the data parcel:
     * <code><br>data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
     * <br>data.writeStrongBinder(\/* binder you want to use at remote *\/);
     * <br>data.writeInt(\/* transact code you want to use *\/);
     * <br>data.writeInterfaceToken(\/* interface name of that binder *\/);
     * <br>\/* write data of the binder call you want*\/</code>
     *
     * @see rikka.shizuku.SystemServiceHelper#obtainParcel(String, String, String)
     * @see rikka.shizuku.SystemServiceHelper#obtainParcel(String, String, String, String)
     */
    public static void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            requireService().asBinder().transact(ShizukuApiConstants.BINDER_TRANSACTION_transact, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Start a new process at remote service, parameters are passed to {@link Runtime#exec(String, String[], java.io.File)}.
     * <p>
     * Note, you may need to read/write streams from RemoteProcess in different threads.
     * </p>
     *
     * @return RemoteProcess holds the binder of remote process
     * @deprecated If transactRemote is not enough for you, use UserService.
     */
    @Deprecated
    public static ShizukuRemoteProcess newProcess(@NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        try {
            return new ShizukuRemoteProcess(requireService().newProcess(cmd, env, dir));
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Returns uid of remote service.
     *
     * @return uid
     * @throws SecurityException if service version below v11 and the app have't get the permission
     */
    public static int getUid() {
        if (serverUid != -1) return serverUid;
        try {
            serverUid = requireService().getUid();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return serverUid;
    }

    /**
     * Returns remote service version.
     *
     * @return server version
     * @throws SecurityException if service version below v11 and the app have't get the permission
     */
    public static int getVersion() {
        if (serverVersion != -1) return serverVersion;
        try {
            serverVersion = requireService().getVersion();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return serverVersion;
    }

    /**
     * Returns if the remote service version belows 11.
     *
     * @return If the remote service version belows 11
     */
    public static boolean isPreV11() {
        return preV11;
    }

    /**
     * Return latest service version when this library was released.
     *
     * @return Latest service version
     * @see Shizuku#getVersion()
     */
    public static int getLatestServiceVersion() {
        return ShizukuApiConstants.SERVER_VERSION;
    }

    /**
     * Check permission at remote service.
     *
     * @param permission permission name
     * @return PackageManager.PERMISSION_DENIED or PackageManager.PERMISSION_GRANTED
     */
    public static int checkPermission(String permission) {
        if (serverUid == 0) return PackageManager.PERMISSION_GRANTED;
        try {
            return requireService().checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Returns SELinux context of Shizuku server process.
     *
     * <p>This API is only meaningful for root app using {@link Shizuku#newProcess(String[], String[], String)}.</p>
     *
     * <p>For adb, context should always be <code>u:r:shell:s0</code>.
     * <br>For root, context depends on su the user uses. E.g., context of Magisk is <code>u:r:magisk:s0</code>.
     * If the user's su does not allow binder calls between su and app, Shizuku will switch to context <code>u:r:shell:s0</code>.
     * </p>
     *
     * @return SELinux context
     * @throws SecurityException if service version below v11 and the app have't get the permission
     * @since added from version 6
     */
    public static String getSELinuxContext() {
        if (serverContext != null) return serverContext;
        try {
            serverContext = requireService().getSELinuxContext();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return serverContext;
    }

    public static class UserServiceArgs {

        final ComponentName componentName;
        int versionCode = 1;
        boolean standalone = true;
        String processName;
        String tag;
        boolean debuggable = false;

        public UserServiceArgs(@NonNull ComponentName componentName) {
            this.componentName = componentName;
        }

        public UserServiceArgs tag(@NonNull String tag) {
            this.tag = tag;
            return this;
        }

        public UserServiceArgs version(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        public UserServiceArgs useStandaloneProcess(String processNameSuffix, boolean debuggable) {
            this.standalone = true;
            this.debuggable = debuggable;
            this.processName = processNameSuffix;
            return this;
        }

        private Bundle forAdd() {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, debuggable);
            options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, versionCode);
            if (standalone) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME,
                        Objects.requireNonNull(processName, "process name suffix must not be null when using standalone process mode"));
            }
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            return options;
        }

        private Bundle forRemove() {
            Bundle options = new Bundle();
            options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, componentName);
            if (tag != null) {
                options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, tag);
            }
            return options;
        }
    }

    /**
     * Run service class from the apk of current app.
     *
     * @since added from version 10
     */
    public static void bindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn) {
        ShizukuServiceConnection connection = ShizukuServiceConnection.getOrCreate(args);
        connection.addConnection(conn);
        try {
            requireService().addUserService(connection, args.forAdd());
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Remove user service.
     *
     * @param remove Remove (kill) the remote user service.
     */
    public static void unbindUserService(@NonNull UserServiceArgs args, @NonNull ServiceConnection conn, boolean remove) {
        ShizukuServiceConnection connection = ShizukuServiceConnection.get(args);
        if (connection != null) {
            connection.removeConnection(conn);
        }
        if (remove) {
            try {
                requireService().removeUserService(null /* (unused) */, args.forRemove());
            } catch (RemoteException e) {
                throw rethrowAsRuntimeException(e);
            }
        }
    }

    /**
     * Request permission.
     *
     * @since added from version 11, use runtime permission APIs for old versions
     */
    public static void requestPermission(int requestCode) {
        try {
            requireService().requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    /**
     * Check if self has permission.
     *
     * @since added from version 11, use runtime permission APIs for old versions
     */
    public static int checkSelfPermission() {
        if (permissionGranted) return PackageManager.PERMISSION_GRANTED;
        try {
            permissionGranted = requireService().checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return permissionGranted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    /**
     * Should show UI with rationale before requesting the permission.
     *
     * @since added from version 11, use runtime permission APIs for old versions
     */
    public static boolean shouldShowRequestPermissionRationale() {
        if (permissionGranted) return false;
        if (shouldShowRequestPermissionRationale) return true;
        try {
            shouldShowRequestPermissionRationale = requireService().shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
        return shouldShowRequestPermissionRationale;
    }

    // --------------------- non-app ----------------------

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void exit() {
        try {
            requireService().exit();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void attachUserService(@NonNull IBinder binder, @NonNull Bundle options) {
        try {
            requireService().attachUserService(binder, options);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, @NonNull Bundle data) {
        try {
            requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static int getFlagsForUid(int uid, int mask) {
        try {
            return requireService().getFlagsForUid(uid, mask);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public static void updateFlagsForUid(int uid, int mask, int value) {
        try {
            requireService().updateFlagsForUid(uid, mask, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

}
