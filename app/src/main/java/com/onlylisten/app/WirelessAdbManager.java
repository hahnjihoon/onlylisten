package com.onlylisten.app;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
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
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

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
