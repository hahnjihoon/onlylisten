package com.onlylisten.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;

public final class WirelessAdbManager extends AbsAdbConnectionManager {
    private final PrivateKey privateKey;
    private final Certificate certificate;

    public WirelessAdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(8, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
        File keyFile = new File(context.getFilesDir(), "wireless-adb.pk8");
        File certFile = new File(context.getFilesDir(), "wireless-adb.crt");
        if (keyFile.isFile() && certFile.isFile()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            try (FileInputStream input = new FileInputStream(certFile)) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            privateKey = keys.getPrivate();
            X500Name subject = new X500Name("CN=OnlyListen Wireless Debugging");
            long now = System.currentTimeMillis();
            certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(subject,
                            new BigInteger(128, new SecureRandom()),
                            new Date(now - 86400000L), new Date(now + 315360000000L),
                            subject, keys.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)));
            Files.write(keyFile.toPath(), privateKey.getEncoded());
            Files.write(certFile.toPath(), certificate.getEncoded());
        }
    }

    @Override
    public boolean connectTls(Context context, long timeoutMillis)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        if (isConnected()) {
            return true;
        }
        NsdManager nsd = context.getSystemService(NsdManager.class);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<InetSocketAddress> endpoint = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean searching = new AtomicBoolean(true);
        AtomicBoolean registered = new AtomicBoolean();
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                registered.set(true);
                if (!searching.get()) {
                    try {
                        nsd.stopServiceDiscovery(this);
                    } catch (RuntimeException ignored) {
                    }
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (!searching.get()) {
                    return;
                }
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            if (!searching.get()) {
                                return;
                            }
                            try {
                                List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34
                                        ? resolved.getHostAddresses() : Collections.singletonList(resolved.getHost());
                                InetAddress selected = null;
                                for (InetAddress candidate : addresses) {
                                    if (candidate != null && NetworkInterface.getByInetAddress(candidate) != null) {
                                        selected = candidate;
                                        if (candidate instanceof Inet4Address) {
                                            break;
                                        }
                                    }
                                }
                                if (selected != null && resolved.getPort() > 0
                                        && endpoint.compareAndSet(null, new InetSocketAddress(selected, resolved.getPort()))) {
                                    ready.countDown();
                                }
                            } catch (IOException error) {
                                failure.set(error);
                            }
                        }

                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            failure.set(new IOException("무선 디버깅 연결 주소 탐색 실패: " + errorCode));
                        }
                    });
                } catch (RuntimeException error) {
                    failure.set(new IOException("무선 디버깅 연결 주소를 검색하지 못했습니다.", error));
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                failure.set(new IOException("무선 디버깅 연결 검색 시작 실패: " + errorCode));
                ready.countDown();
            }

            @Override
            public void onDiscoveryStopped(String type) {
                registered.set(false);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
            ready.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            throw new IOException("무선 디버깅 연결 검색을 시작하지 못했습니다.", error);
        } finally {
            searching.set(false);
            if (registered.get()) {
                try {
                    nsd.stopServiceDiscovery(discovery);
                } catch (RuntimeException ignored) {
                }
            }
        }
        InetSocketAddress address = endpoint.get();
        if (address == null) {
            if (failure.get() != null) {
                throw failure.get();
            }
            throw new IOException("현재 무선 디버깅 연결 포트를 찾지 못했습니다. Wi-Fi와 무선 디버깅이 켜져 있는지 확인해 주세요.");
        }
        return connect(address.getAddress().getHostAddress(), address.getPort());
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "OnlyListen";
    }
}
