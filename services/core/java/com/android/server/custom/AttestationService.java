/*
 * Copyright (C) 2024 The LeafOS Project
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.custom;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.security.keybox.AttestationCertificates;
import com.android.internal.security.keybox.IKeyboxAttestationService;
import com.android.internal.security.keybox.KeyboxKeyParameters;
import android.system.keystore2.KeyDescriptor;

import com.android.internal.util.custom.CustomUtils;
import com.android.internal.util.custom.KeyboxChainGenerator;
import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;
import com.android.internal.util.custom.KeyboxUtils;
import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

public final class AttestationService extends SystemService {
    private static final String TAG = AttestationService.class.getSimpleName();
    private static final String API =
            Resources.getSystem().getString(com.android.internal.R.string.config_pifUpdateUrl);

    private static final String DATA_FILE = "gms_certified_props.json";

    private static final long INITIAL_DELAY = 0;
    private static final long INTERVAL = 5;

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final Boolean sDisableGmsProps =
            SystemProperties.getBoolean("persist.sys.pihooks.disable.gms_props", false);

    private final Context mContext;
    private final ScheduledExecutorService mScheduler;

    private final KeyboxAttestationServiceImpl mKeyboxAttestationService;

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mKeyboxAttestationService = new KeyboxAttestationServiceImpl(context);
        mScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onStart() {
        publishBinderService("android.security.keybox", mKeyboxAttestationService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (!sDisableGmsProps && CustomUtils.isPackageInstalled(mContext, "com.google.android.gms")
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling the service");
            mScheduler.scheduleAtFixedRate(
                    new FetchGmsCertifiedProps(), INITIAL_DELAY, INTERVAL, TimeUnit.MINUTES);
        }
    }

    private String fetchProps() {
        try {
            URL url = new URI(API).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                try (BufferedReader reader = new BufferedReader(
                             new InputStreamReader(urlConnection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making an API request", e);
            return null;
        }
    }

    private static boolean isValidProps(String props) {
        if (TextUtils.isEmpty(props)) {
            return false;
        }

        try {
            return new JSONObject(props).length() > 0;
        } catch (JSONException e) {
            Log.e(TAG, "Fetched PIF data is not valid JSON", e);
            return false;
        }
    }

    private boolean isInternetConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network nw = cm.getActiveNetwork();
        if (nw == null)
            return false;
        NetworkCapabilities actNw = cm.getNetworkCapabilities(nw);
        return actNw != null
                && (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
    }

    private void dlog(String message) {
        if (DEBUG)
            Log.d(TAG, message);
    }

    private class FetchGmsCertifiedProps implements Runnable {
        @Override
        public void run() {
            try {
                dlog("FetchGmsCertifiedProps started");

                if (!isInternetConnected()) {
                    Log.e(TAG, "Internet unavailable");
                    return;
                }

                String savedProps = Settings.Secure.getString(
                        mContext.getContentResolver(), Settings.Secure.FETCHED_PIF);
                String props = fetchProps();

                if (isValidProps(props) && !TextUtils.equals(savedProps, props)) {
                    dlog("Found new props");
                    Settings.Secure.putString(
                            mContext.getContentResolver(), Settings.Secure.FETCHED_PIF, props);
                    dlog("FetchGmsCertifiedProps completed");
                } else {
                    dlog("No change in props");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in FetchGmsCertifiedProps", e);
            }
        }
    }

    private static class KeyboxAttestationServiceImpl extends IKeyboxAttestationService.Stub {
        private static final int MAX_KEYBOX_ALIAS_UTF16_CODE_UNITS = 1024;
        private static final int MAX_KEYBOX_BLOB_SIZE_BYTES = 32 * 1024;
        private static final int MAX_KEYBOX_INT_ARRAY_ITEMS = 1024;
        private static final long MAX_KEYBOX_REQUEST_SIZE_BYTES = 64 * 1024;

        private final Context mContext;

        KeyboxAttestationServiceImpl(Context context) {
            mContext = context;
        }

        private static AttestationCertificates emptyCertificates() {
            AttestationCertificates certs = new AttestationCertificates();
            certs.privateKey = new byte[0];
            certs.certificate = new byte[0];
            certs.certificateChain = new byte[0];
            return certs;
        }

        private static AttestationCertificates buildCertificates(List<Certificate> chain,
                byte[] privateKey) throws Exception {
            AttestationCertificates certs = emptyCertificates();
            certs.privateKey = privateKey != null ? privateKey : certs.privateKey;
            certs.certificate = chain.get(0).getEncoded();
            if (chain.size() > 1) {
                certs.certificateChain = KeyboxUtils.toCertificateChainBytes(
                        chain.subList(1, chain.size()).toArray(new Certificate[0]));
            }
            return certs;
        }

        private void enforcePermission() {
            int callingUid = Binder.getCallingUid();
            if (callingUid != Process.KEYSTORE_UID) {
                throw new SecurityException("Only Keystore daemon can call this service");
            }
        }

        private static boolean isBlobWithinLimit(byte[] value) {
            return value == null || value.length <= MAX_KEYBOX_BLOB_SIZE_BYTES;
        }

        private static boolean isKeyboxRequestWithinLimits(String alias,
                KeyboxKeyParameters params, byte[] payload) {
            if (params == null || !isBlobWithinLimit(payload)
                    || !isBlobWithinLimit(params.certificateSerial)
                    || !isBlobWithinLimit(params.certificateSubject)
                    || !isBlobWithinLimit(params.attestationChallenge)
                    || !isBlobWithinLimit(params.brand) || !isBlobWithinLimit(params.device)
                    || !isBlobWithinLimit(params.product)
                    || !isBlobWithinLimit(params.manufacturer)
                    || !isBlobWithinLimit(params.model)) return false;
            int aliasLength = alias == null ? 0 : alias.length();
            int curveNameLength = params.ecCurveName == null ? 0 : params.ecCurveName.length();
            if (aliasLength > MAX_KEYBOX_ALIAS_UTF16_CODE_UNITS
                    || curveNameLength > MAX_KEYBOX_ALIAS_UTF16_CODE_UNITS
                    || (params.purpose != null && params.purpose.length > MAX_KEYBOX_INT_ARRAY_ITEMS)
                    || (params.digest != null && params.digest.length > MAX_KEYBOX_INT_ARRAY_ITEMS)) {
                return false;
            }
            long requestSize = ((long) aliasLength + curveNameLength) * Character.BYTES;
            requestSize += (long) (params.purpose == null ? 0 : params.purpose.length) * Integer.BYTES;
            requestSize += (long) (params.digest == null ? 0 : params.digest.length) * Integer.BYTES;
            byte[][] blobs = {params.certificateSerial, params.certificateSubject,
                    params.attestationChallenge, params.brand, params.device, params.product,
                    params.manufacturer, params.model, payload};
            for (byte[] blob : blobs) {
                requestSize += blob == null ? 0 : blob.length;
                if (requestSize > MAX_KEYBOX_REQUEST_SIZE_BYTES) return false;
            }
            return requestSize <= MAX_KEYBOX_REQUEST_SIZE_BYTES;
        }

        private static KeyDescriptor buildDescriptor(String alias, int domain, long nspace) {
            KeyDescriptor descriptor = new KeyDescriptor();
            descriptor.alias = alias;
            descriptor.domain = domain;
            descriptor.nspace = nspace;
            descriptor.blob = null;
            return descriptor;
        }

        @Override
        public boolean shouldUseKeybox(int targetUid) {
            enforcePermission();
            Context userContext = mContext.createContextAsUser(
                    UserHandle.of(UserHandle.getUserId(targetUid)), 0);
            String excluded = Settings.Secure.getString(
                    userContext.getContentResolver(), Settings.Secure.KEYBOX_EXCLUDED_PACKAGES);
            if (excluded == null || excluded.isBlank()) {
                return true;
            }

            String[] packages = mContext.getPackageManager().getPackagesForUid(targetUid);
            if (packages == null || packages.length == 0) {
                return true;
            }

            List<String> excludedPackages = Arrays.asList(excluded.split(":"));
            return Collections.disjoint(Arrays.asList(packages), excludedPackages);
        }

        @Override
        public AttestationCertificates generateCertificateChain(int targetUid, String alias,
                int domain, long nspace,
                KeyboxKeyParameters params, byte[] leafCertificate) {
            enforcePermission();
            if (!isKeyboxRequestWithinLimits(alias, params, leafCertificate)) {
                Log.w(TAG, "Rejected oversized Keybox certificate-chain request");
                return emptyCertificates();
            }
            try {
                KeyGenParameters keyGenParams = new KeyGenParameters(params);
                KeyDescriptor descriptor = buildDescriptor(alias, domain, nspace);
                List<Certificate> chain = KeyboxChainGenerator.generateCertChainFromCert(
                        targetUid, descriptor, keyGenParams, leafCertificate);

                if (chain == null || chain.isEmpty()) {
                    return emptyCertificates();
                }

                return buildCertificates(chain, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate certificate chain", e);
                return emptyCertificates();
            }
        }

        @Override
        public AttestationCertificates generateSoftwareKey(int targetUid, String alias,
                int domain, long nspace, KeyboxKeyParameters params, byte[] entropy) {
            enforcePermission();
            if (!isKeyboxRequestWithinLimits(alias, params, entropy)) {
                Log.w(TAG, "Rejected oversized Keybox software-key request");
                return emptyCertificates();
            }
            try {
                KeyGenParameters keyGenParams = new KeyGenParameters(params);
                KeyDescriptor descriptor = buildDescriptor(alias, domain, nspace);

                KeyboxChainGenerator.GeneratedKeyMaterial material =
                        KeyboxChainGenerator.generateKeyMaterial(
                                targetUid, descriptor, keyGenParams, entropy);

                if (material == null || material.keyPair == null
                        || material.certificateChain == null
                        || material.certificateChain.isEmpty()) {
                    return emptyCertificates();
                }

                byte[] privateKey = material.keyPair.getPrivate().getEncoded();
                if (privateKey == null || privateKey.length == 0) {
                    return emptyCertificates();
                }

                return buildCertificates(material.certificateChain, privateKey);
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate software key and certificates", e);
                return emptyCertificates();
            }
        }
    }
}
