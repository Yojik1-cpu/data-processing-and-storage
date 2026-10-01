package j1;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Date;

final class KeyIssuer {
    private KeyIssuer() {}

    static byte[] issue(String commonName, KeyPairGenerator generator, PrivateKey ca, String issuerDn) throws Exception {
        SigningKey.provider();
        KeyPair pair = generator.generateKeyPair();
        X500NameBuilder subject = new X500NameBuilder();
        subject.addRDN(BCStyle.CN, commonName);
        Date now = new Date();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                new X500Name(issuerDn),
                serial(),
                new Date(now.getTime() - 5L * 60 * 1000),
                new Date(now.getTime() + 365L * 24 * 60 * 60 * 1000),
                subject.build(),
                SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca));
        return frame(pem(pair.getPrivate()), pem(holder));
    }

    private static BigInteger serial() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[8];
        BigInteger value;
        do {
            random.nextBytes(bytes);
            value = new BigInteger(1, bytes);
        } while (value.signum() == 0);
        return value;
    }

    private static byte[] pem(Object value) throws Exception {
        StringWriter text = new StringWriter();
        if (value instanceof PrivateKey key) {
            try (PemWriter writer = new PemWriter(text)) {
                writer.writeObject(new PemObject("PRIVATE KEY", key.getEncoded()));
            }
        } else {
            try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
                writer.writeObject(value);
            }
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] frame(byte[] keyPem, byte[] certPem) {
        ByteBuffer buf = ByteBuffer.allocate(8 + keyPem.length + certPem.length);
        buf.putInt(keyPem.length);
        buf.put(keyPem);
        buf.putInt(certPem.length);
        buf.put(certPem);
        return buf.array();
    }
}
