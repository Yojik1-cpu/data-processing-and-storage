package j1;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.RSAPrivateCrtKey;

final class SigningKey {
    private SigningKey() {}

    static void provider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    static void generate(Path path) throws Exception {
        provider();
        Path absolute = path.toAbsolutePath();
        if (Files.exists(absolute)) {
            throw new IllegalStateException("file already exists: " + absolute);
        }
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalStateException("missing directory: " + parent);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        StringWriter text = new StringWriter();
        try (PemWriter writer = new PemWriter(text)) {
            writer.writeObject(new PemObject("PRIVATE KEY", generator.generateKeyPair().getPrivate().getEncoded()));
        }
        Files.writeString(absolute, text.toString(), StandardCharsets.US_ASCII);
    }

    static RSAPrivateCrtKey load(Path path) throws IOException {
        provider();
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(path)))) {
            Object read = parser.readObject();
            if (!(read instanceof PrivateKeyInfo info)) {
                throw new IllegalStateException("cannot read key: " + path);
            }
            var key = new JcaPEMKeyConverter().getPrivateKey(info);
            if (!(key instanceof RSAPrivateCrtKey rsa)) {
                throw new IllegalStateException("cannot read key: " + path);
            }
            return rsa;
        }
    }
}
