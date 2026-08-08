package uz.hamkorbank.commhub.domain.service;

import java.util.Set;
import java.util.stream.Collectors;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.SmsEncoding;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Computes the encoding, the billed character count and the number of SMS parts (MP-06, §18.3).
 *
 * <p>Rules reproduced from §18.3:
 *
 * <ul>
 *   <li>GSM-7: 160 characters in a single SMS, 153 per part of a concatenated one;
 *   <li>UCS-2: 70 and 67 respectively;
 *   <li>a single character outside the GSM-7 alphabet switches the whole message to UCS-2;
 *   <li>{@code ^ { } \ [ ~ ] | €} occupy two GSM-7 characters (escape + character).
 * </ul>
 *
 * <p>Stateless and side-effect free, so one instance can serve every virtual thread (AR-07).
 */
public final class SegmentCalculator {

    /** GSM 03.38 default alphabet. */
    private static final String GSM7_BASIC_CHARACTERS = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./"
            + "0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /** GSM 03.38 extension table: each of these costs two characters (§18.3). */
    private static final String GSM7_EXTENDED_CHARACTERS = "\f^{}\\[~]|€";

    private static final Set<Character> GSM7_BASIC = toCharacterSet(GSM7_BASIC_CHARACTERS);
    private static final Set<Character> GSM7_EXTENDED = toCharacterSet(GSM7_EXTENDED_CHARACTERS);

    /** Calculates the segmentation of an SMS text. */
    public SmsSegmentation calculate(String text) {
        Guard.notNull(text, "text");
        SmsEncoding encoding = detectEncoding(text);
        int characterCount = encoding == SmsEncoding.GSM7 ? gsm7Length(text) : text.length();
        return new SmsSegmentation(encoding, characterCount, segmentCount(characterCount, encoding));
    }

    /** Calculates the segmentation of the text of an SMS payload. */
    public SmsSegmentation calculate(SmsContent content) {
        Guard.notNull(content, "content");
        return calculate(content.text());
    }

    /** Encoding the text will be sent in: one non-GSM-7 character forces UCS-2 (§18.3). */
    public SmsEncoding detectEncoding(String text) {
        Guard.notNull(text, "text");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!GSM7_BASIC.contains(character) && !GSM7_EXTENDED.contains(character)) {
                return SmsEncoding.UCS2;
            }
        }
        return SmsEncoding.GSM7;
    }

    /** Billed GSM-7 length: escape characters count as two (§18.3). */
    private static int gsm7Length(String text) {
        int length = 0;
        for (int index = 0; index < text.length(); index++) {
            length += GSM7_EXTENDED.contains(text.charAt(index)) ? 2 : 1;
        }
        return length;
    }

    private static int segmentCount(int characterCount, SmsEncoding encoding) {
        if (characterCount <= encoding.singleSegmentCapacity()) {
            return 1;
        }
        int perSegment = encoding.concatenatedSegmentCapacity();
        return (characterCount + perSegment - 1) / perSegment;
    }

    private static Set<Character> toCharacterSet(String characters) {
        return characters.chars().mapToObj(value -> (char) value).collect(Collectors.toUnmodifiableSet());
    }
}
