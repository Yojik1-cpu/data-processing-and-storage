package j1;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class KeyIssuerTest {
    @Test
    void issuesFramedCertificate() throws Exception {
        Path dir = Files.createTempDirectory("j1-ca");
        Path ca = dir.resolve("ca.key");
        SigningKey.generate(ca);
        assertThrows(IllegalStateException.class, () -> SigningKey.generate(ca));
        Path missing = dir.resolve("no-such-dir").resolve("ca.key");
        assertThrows(IllegalStateException.class, () -> SigningKey.generate(missing));

        RSAPrivateCrtKey signing = SigningKey.load(ca);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] framed = KeyIssuer.issue("a,b", generator, signing, "CN=DPS CA");
        ByteBuffer buf = ByteBuffer.wrap(framed);
        byte[] keyPem = readBlock(buf);
        byte[] certPem = readBlock(buf);
        assertEquals(0, buf.remaining());

        String keyText = new String(keyPem, StandardCharsets.US_ASCII);
        assertTrue(keyText.contains("BEGIN PRIVATE KEY"));
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pemDer(keyText)));
        assertEquals(2048, privateKey.getModulus().bitLength());

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certPem));
        assertEquals("CN=a\\,b", cert.getSubjectX500Principal().getName());
        assertEquals("CN=DPS CA", cert.getIssuerX500Principal().getName());
        assertTrue(cert.getCriticalExtensionOIDs() == null || cert.getCriticalExtensionOIDs().isEmpty());
        assertTrue(cert.getNonCriticalExtensionOIDs() == null || cert.getNonCriticalExtensionOIDs().isEmpty());
        Date now = new Date();
        assertTrue(cert.getNotBefore().before(now));
        assertTrue(cert.getNotBefore().after(new Date(now.getTime() - 10L * 60 * 1000)));
        assertTrue(cert.getNotAfter().after(new Date(now.getTime() + 364L * 24 * 60 * 60 * 1000)));
        assertTrue(cert.getNotAfter().before(new Date(now.getTime() + 366L * 24 * 60 * 60 * 1000)));

        PublicKey caPublic = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(signing.getModulus(), signing.getPublicExponent()));
        cert.verify(caPublic);
        RSAPublicKey certPublic = (RSAPublicKey) cert.getPublicKey();
        assertEquals(privateKey.getModulus(), certPublic.getModulus());
        assertEquals(privateKey.getPublicExponent(), certPublic.getPublicExponent());
        PublicKey fromCert = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(cert.getPublicKey().getEncoded()));
        assertEquals(cert.getPublicKey(), fromCert);
    }

    private static byte[] readBlock(ByteBuffer buf) {
        int length = buf.getInt();
        if (length <= 0 || length > 1_048_576) {
            fail("bad length");
        }
        byte[] block = new byte[length];
        buf.get(block);
        return block;
    }

    private static byte[] pemDer(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(body);
    }
}
