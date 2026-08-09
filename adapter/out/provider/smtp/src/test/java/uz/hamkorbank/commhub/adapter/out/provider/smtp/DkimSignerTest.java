package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * The DKIM signature, checked the way a recipient checks it (EM-03, RFC 6376).
 *
 * <p>A signature test that only asserts "a header appeared" proves nothing — the whole failure mode of DKIM
 * is a signature that is present and does not verify. So the test generates a key pair, signs, and then
 * verifies the signature exactly as a verifying MTA would: rebuild the hash input from the canonicalised
 * headers and body, and check it against the public key.
 */
class DkimSignerTest {

    private static final Pattern TAG = Pattern.compile("(?:^|;)\\s*([a-z]+)=([^;]*)");

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    @DisplayName("EM-03: the signature verifies against the public key of the selector")
    void signatureVerifies() throws Exception {
        // Arrange
        MimeMessage message = message();
        byte[] serialized = SmtpMessageCodec.serialize(message);
        SmtpProperties.Dkim settings = settings();

        // Act
        String header = DkimSigner.signature(serialized, settings, keyPair.getPrivate(), FixedInstants.NOW);

        // Assert
        assertThat(verify(serialized, header)).isTrue();
        Map<String, String> tags = tags(header);
        assertThat(tags)
                .containsEntry("v", "1")
                .containsEntry("a", "rsa-sha256")
                .containsEntry("c", "relaxed/relaxed");
        assertThat(tags.get("d")).isEqualTo("hamkorbank.uz");
        assertThat(tags.get("s")).isEqualTo("hub");
        assertThat(tags.get("h")).contains("from").contains("subject").contains("message-id");
    }

    @Test
    @DisplayName("EM-03: a message whose body was altered after signing no longer verifies")
    void alteredBodyBreaksTheSignature() throws Exception {
        // Arrange
        byte[] serialized = SmtpMessageCodec.serialize(message());
        String header = DkimSigner.signature(serialized, settings(), keyPair.getPrivate(), FixedInstants.NOW);
        // Дописанная строка тела — самая безобидная правка, какую может сделать шлюз по дороге;
        // подпись обязана её заметить (текст письма в base64, поэтому подменяем не слово, а байты).
        byte[] altered =
                (new String(serialized, StandardCharsets.UTF_8) + "tampered\r\n").getBytes(StandardCharsets.UTF_8);

        // Act + Assert
        assertThat(verify(altered, header)).isFalse();
    }

    @Test
    @DisplayName("RFC 6376 §3.4.4: relaxed body canonicalisation collapses whitespace and trailing empty lines")
    void canonicalisesTheBody() {
        // Act + Assert
        assertThat(canonical(" C \r\nD \t E\r\n\r\n\r\n")).isEqualTo(" C\r\nD E\r\n");
        assertThat(canonical("Hello")).isEqualTo("Hello\r\n");
        assertThat(canonical("")).isEmpty();
        assertThat(canonical("\r\n")).isEmpty();
    }

    @Test
    @DisplayName("RFC 6376 §3.4.2: relaxed header canonicalisation lower-cases, unfolds and collapses")
    void canonicalisesAHeader() {
        // Act + Assert
        assertThat(DkimSigner.canonicalizeHeader("Subject", " Ваша  выписка ")).isEqualTo("subject:Ваша выписка");
        assertThat(DkimSigner.canonicalizeHeader("A", " X\r\n\tY")).isEqualTo("a:X Y");
    }

    @Test
    @DisplayName("SEC-04: the key is read from the secret store as PKCS#8 PEM")
    void parsesThePemKey() throws Exception {
        // Arrange
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        // Act + Assert
        assertThat(DkimSigner.parsePrivateKey(pem).getAlgorithm()).isEqualTo("RSA");
    }

    /** Verification as a receiving MTA does it: rebuild the input, check the signature (RFC 6376 §6.1.3). */
    private static boolean verify(byte[] serializedMessage, String signatureHeader) throws Exception {
        Map<String, String> tags = tags(signatureHeader);
        DkimSigner.Split split = DkimSigner.Split.of(serializedMessage);
        String bodyHash = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(DkimSigner.canonicalizeBody(split.body())));
        if (!bodyHash.equals(tags.get("bh"))) {
            return false;
        }
        StringBuilder input = new StringBuilder();
        for (String name : List.of(tags.get("h").split(":"))) {
            input.append(DkimSigner.canonicalizeHeader(name, split.headers().get(name)))
                    .append("\r\n");
        }
        String withoutSignature = signatureHeader.substring(0, signatureHeader.lastIndexOf("b=") + 2);
        input.append(DkimSigner.canonicalizeHeader(DkimSigner.HEADER, withoutSignature));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(input.toString().getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(tags.get("b")));
    }

    private static String canonical(String body) {
        return new String(DkimSigner.canonicalizeBody(body.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static Map<String, String> tags(String header) {
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher matcher = TAG.matcher(header);
        while (matcher.find()) {
            tags.put(matcher.group(1), matcher.group(2).trim());
        }
        return tags;
    }

    private static SmtpProperties.Dkim settings() {
        return new SmtpProperties.Dkim(true, "hamkorbank.uz", "hub", "dkim/private-key", null);
    }

    private static MimeMessage message() throws Exception {
        Session session = Session.getInstance(SmtpProperties.defaults().sessionProperties());
        return new SmtpMessageCodec(new AttachmentStore(AttachmentStoreProperties.disabled()))
                .encode(
                        session,
                        EmailSubmissions.textOnly(MessageId.newId()),
                        new SmtpProperties.Sending("no-reply@hamkorbank.uz", "Hamkorbank", null, null),
                        Date.from(FixedInstants.NOW));
    }
}
