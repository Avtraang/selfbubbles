package io.github.avtraang.selfbubbles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The pure vCard reader behind the contact-card bubble (VCard.kt). Invented people and 555 numbers only. */
class VCardTest {

    // A few bytes that start like a JPEG, so the "declared type" and the sniffed type can be told apart.
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        "JFIF-not-really-a-picture-but-long-enough-to-fold-over-several-lines-when-base64-encoded".toByteArray()
    private val fakePng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)

    /** Base64 of [bytes], folded the way the iPhone writes it: 76 columns, continuation lines indented by one space. */
    private fun folded(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).chunked(76).joinToString("\r\n ")

    private fun iphoneCard(): String = listOf(
        "BEGIN:VCARD",
        "VERSION:3.0",
        "PRODID:-//Apple Inc.//iPhone OS 17.0//EN",
        "N:Quimby;Dana;Lee;Dr.;Jr.",
        "FN:Dr. Dana Lee Quimby Jr.",
        "ORG:Quimby Widgets;Research",
        "TITLE:Chief Tinkerer",
        "item1.EMAIL;type=INTERNET;type=pref:dana@example.com",
        "item1.X-ABLabel:_\$!<Other>!\$_",
        "EMAIL;type=INTERNET;type=WORK:dq@work.example.com",
        "TEL;type=CELL;type=VOICE;type=pref:(555) 010-0001",
        "TEL;type=IPHONE;type=CELL;type=VOICE:+1 555 010 0002",
        "TEL;type=HOME;type=VOICE:555-010-0003",
        "item2.TEL:555-010-0004",
        "item2.X-ABLabel:Cabin",
        "item3.ADR;type=HOME;type=pref:;;12 Example Lane;Springfield;ZZ;00000;USA",
        "item3.X-ABADR:us",
        "item4.URL;type=pref:https://example.com/dana",
        "item4.X-ABLabel:_\$!<HomePage>!\$_",
        "NOTE:Likes folded lines.\\nSecond line.",
        "BDAY:1985-04-12",
        "PHOTO;ENCODING=b;TYPE=JPEG:" + folded(fakeJpeg),
        "END:VCARD",
    ).joinToString("\r\n") + "\r\n"

    // ---- detection ----

    @Test fun detect_mimeAnyCaseAndParameters() {
        assertTrue(isContactCardAttachment("text/vcard", null))
        assertTrue(isContactCardAttachment("TEXT/X-VCARD", "blob"))
        assertTrue(isContactCardAttachment("text/vcard; charset=utf-8", "x.bin"))
        assertTrue(isContactCardAttachment("  text/x-vcard ;name=\"a.vcf\"", null))
    }

    @Test fun detect_nameOnly() {
        assertTrue(isContactCardAttachment(null, "Dana.vcf"))
        assertTrue(isContactCardAttachment("", "DANA.VCF"))
        assertTrue(isContactCardAttachment("application/octet-stream", "dana.vCard"))
        assertTrue(isContactCardAttachment("*/*", "dana.vcf  "))
    }

    @Test fun detect_neither() {
        assertFalse(isContactCardAttachment("image/jpeg", "photo.jpg"))
        assertFalse(isContactCardAttachment("text/plain", "vcf"))
        assertFalse(isContactCardAttachment("text/vcardx", "notes.txt"))
        assertFalse(isContactCardAttachment(null, ".vcf"))
        assertFalse(isContactCardAttachment(null, null))
        assertFalse(isContactCardAttachment("", ""))
    }

    @Test fun fileName_isSanitisedAndEndsInVcf() {
        assertEquals("Dana.vcf", sanitizeContactFileName("Dana.VCF"))
        assertEquals("contact.vcf", sanitizeContactFileName(null))
        assertEquals("contact.vcf", sanitizeContactFileName("../../"))
        assertEquals("a_b.vcf", sanitizeContactFileName("a_b"))
        assertTrue(contactCardCacheDirName("https://relay.example/attachment/1").startsWith("vcard-"))
    }

    // ---- vCard 3.0 as the iPhone sends it ----

    @Test fun iphone30_allFields() {
        val cards = parseVCards(iphoneCard())
        assertEquals(1, cards.size)
        val c = cards[0]
        assertEquals("Dr. Dana Lee Quimby Jr.", c.displayName)
        assertEquals("Dana", c.givenName)
        assertEquals("Quimby", c.familyName)
        assertEquals("Quimby Widgets", c.org)
        assertEquals("Chief Tinkerer", c.title)
        assertEquals(
            listOf(
                Labeled("Mobile", "(555) 010-0001"),
                Labeled("Mobile", "+1 555 010 0002"),
                Labeled("Home", "555-010-0003"),
                Labeled("Cabin", "555-010-0004"),
            ),
            c.phones,
        )
        assertEquals(listOf(Labeled("Other", "dana@example.com"), Labeled("Work", "dq@work.example.com")), c.emails)
        assertEquals(listOf(Labeled("Home", "12 Example Lane, Springfield, ZZ, 00000, USA")), c.addresses)
        assertEquals(listOf(Labeled("HomePage", "https://example.com/dana")), c.urls)
        assertEquals("Likes folded lines.\nSecond line.", c.note)
        assertEquals("1985-04-12", c.birthday)
        assertNotNull(c.photo)
        assertArrayEquals(fakeJpeg, c.photo)
        assertEquals("image/jpeg", c.photoMime)
    }

    @Test fun iphone30_lfLineEndingsReadTheSame() {
        val cards = parseVCards(iphoneCard().replace("\r\n", "\n"))
        assertEquals(1, cards.size)
        assertEquals("Dr. Dana Lee Quimby Jr.", cards[0].displayName)
        assertArrayEquals(fakeJpeg, cards[0].photo)
    }

    @Test fun foldedLines_tabAndSpaceContinuations() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Fold\r\n ed Na\r\n\tme\r\nTEL;TYPE=WORK:555-\r\n 010-0005\r\nEND:VCARD\r\n"
        val c = parseVCards(text).single()
        assertEquals("Folded Name", c.displayName)
        assertEquals(listOf(Labeled("Work", "555-010-0005")), c.phones)
    }

    @Test fun escapedCommasSemicolonsAndNewlines() {
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "FN:Quimby\\, Dana",
            "N:Quimby\\;Esq.;Dana;;;",
            "ADR;TYPE=WORK:;;1\\, Example St\\; Suite 2;Town;;;",
            "NOTE:a\\;b\\,c\\\\d\\ne",
            "EMAIL:dana@example.com",
            "END:VCARD",
        ).joinToString("\n")
        val c = parseVCards(text).single()
        assertEquals("Quimby, Dana", c.displayName)
        assertEquals("Quimby;Esq.", c.familyName)
        assertEquals("Dana", c.givenName)
        assertEquals(listOf(Labeled("Work", "1, Example St; Suite 2, Town")), c.addresses)
        assertEquals("a;b,c\\d\ne", c.note)
    }

    // ---- vCard 2.1 with quoted-printable ----

    @Test fun android21_quotedPrintableUtf8WithSoftBreaks() {
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Garc=C3=ADa;Jos=C3=A9;;;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Jos=C3=A9 Garc=C3=ADa",
            "TEL;CELL;VOICE:555-010-0006",
            "TEL;WORK;FAX:555-010-0007",
            "EMAIL;PREF;INTERNET:jose@example.com",
            "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Caf=C3=A9 au lait=20=",
            "with Ren=C3=A9e =",
            "and Zo=C3=AB",
            "ORG:Caf=C3=A9 Ltd",
            "END:VCARD",
        ).joinToString("\r\n")
        val c = parseVCards(text).single()
        assertEquals("José García", c.displayName)
        assertEquals("José", c.givenName)
        assertEquals("García", c.familyName)
        assertEquals(listOf(Labeled("Mobile", "555-010-0006"), Labeled("Fax", "555-010-0007")), c.phones)
        assertEquals(listOf(Labeled("Other", "jose@example.com")), c.emails)
        assertEquals("Café au lait with Renée and Zoë", c.note)
        assertEquals("Caf=C3=A9 Ltd", c.org)   // not declared quoted-printable: left as written
    }

    @Test fun android21_quotedPrintableLatin1AndBase64Photo() {
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "FN;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:Jos=E9 Garc=EDa",
            "TEL;HOME:555-010-0008",
            "PHOTO;ENCODING=BASE64;PNG:" + folded(fakePng),
            "",
            "END:VCARD",
        ).joinToString("\r\n")
        val c = parseVCards(text).single()
        assertEquals("José García", c.displayName)
        assertEquals(listOf(Labeled("Home", "555-010-0008")), c.phones)
        assertArrayEquals(fakePng, c.photo)
        assertEquals("image/png", c.photoMime)
    }

    @Test fun quotedPrintable_brokenEscapeIsKept() {
        val text = "BEGIN:VCARD\nFN;ENCODING=QUOTED-PRINTABLE:50=% off =3D=4\nTEL:555-010-0009\nEND:VCARD"
        val c = parseVCards(text).single()
        assertEquals("50=% off ==4", c.displayName)
        assertEquals("555-010-0009", c.phones.single().value)
    }

    // ---- vCard 4.0 ----

    @Test fun vcard40_uriValuesQuotedTypesAndDataPhoto() {
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:4.0",
            "FN:Robin Example",
            "N:Example;Robin;;;",
            "TEL;VALUE=uri;TYPE=\"voice,cell\";PREF=1:tel:+1-555-010-0010",
            "TEL;VALUE=uri;TYPE=work:tel:+1-555-010-0011",
            "EMAIL;TYPE=home:robin@example.com",
            "ADR;TYPE=home;LABEL=\"1 Main St\\nTown\":;;1 Main St;Town;;;",
            "URL:https://example.org",
            "BDAY:19850412",
            "PHOTO:data:image/png;base64," + Base64.getEncoder().encodeToString(fakePng),
            "END:VCARD",
        ).joinToString("\r\n")
        val c = parseVCards(text).single()
        assertEquals("Robin Example", c.displayName)
        assertEquals(listOf(Labeled("Mobile", "+1-555-010-0010"), Labeled("Work", "+1-555-010-0011")), c.phones)
        assertEquals(listOf(Labeled("Home", "robin@example.com")), c.emails)
        assertEquals(listOf(Labeled("Home", "1 Main St, Town")), c.addresses)
        assertEquals(listOf(Labeled("Other", "https://example.org")), c.urls)
        assertEquals("1985-04-12", c.birthday)
        assertArrayEquals(fakePng, c.photo)
        assertEquals("image/png", c.photoMime)
    }

    @Test fun birthday_formsAreNormalised() {
        fun bday(v: String) = parseVCards("BEGIN:VCARD\nFN:X\nBDAY:$v\nEND:VCARD").single().birthday
        assertEquals("1985-04-12", bday("19850412"))
        assertEquals("1985-04-12", bday("1985-04-12T00:00:00Z"))
        assertEquals("--04-12", bday("--0412"))   // the year-less form the Contacts app reads
        assertEquals("--04-12", bday("--04-12"))
        assertEquals("April 12", bday("April 12"))
        assertEquals("1604-04-12", bday("1604-04-12"))   // no omit-year marker: taken at face value
    }

    @Test fun birthday_appleOmitYearPlaceholderIsDropped() {
        val c = parseVCards("BEGIN:VCARD\nVERSION:3.0\nFN:X\nBDAY;X-APPLE-OMIT-YEAR=1604:1604-04-12\nTEL:555-010-0030\nEND:VCARD").single()
        assertEquals("--04-12", c.birthday)
        assertEquals("04-12", contactBirthdayLabel(c.birthday!!))
        assertEquals("1985-04-12", contactBirthdayLabel("1985-04-12"))
        // The marker only strips its own year: a real year next to it is kept.
        val real = parseVCards("BEGIN:VCARD\nFN:X\nBDAY;X-APPLE-OMIT-YEAR=1604:1985-04-12\nEND:VCARD").single()
        assertEquals("1985-04-12", real.birthday)
    }

    @Test fun quotedPrintable_softBreakContinuationKeepsItsLeadingSpace() {
        // 2.1 producers that write raw ASCII break a value before a space; the space is data, not a fold marker.
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Caf=C3=A9 au lait=\r\n with Zo=C3=AB\r\nTEL:555-010-0031\r\nEND:VCARD"
        assertEquals("Café au lait with Zoë", parseVCards(text).single().displayName)
        // A head that is itself folded (no colon yet) still folds the ordinary way.
        val foldedHead = "BEGIN:VCARD\nFN;CHARSET=UTF-8;ENCODING=\n QUOTED-PRINTABLE:Zo=C3=AB=\n  Two\nEND:VCARD"
        assertEquals("Zoë  Two", parseVCards(foldedHead).single().displayName)
    }

    @Test fun quotedPrintable_hugeRunOfSoftBreaksIsLinear() {
        // A long parameter head followed by hundreds of thousands of "=" soft-break lines, just under the
        // input cap: rescanning the head per break took over a minute; one pass must finish in moments.
        val head = "NOTE;ENCODING=QUOTED-PRINTABLE;X=" + "a".repeat(300_000) + ":v="
        val text = "BEGIN:VCARD\nFN:X\nTEL:555-010-0032\n" + head + "\n" + "=\n".repeat(340_000) + "end\nEND:VCARD"
        assertTrue(text.length < VCARD_MAX_INPUT_BYTES)
        val started = System.nanoTime()
        val c = parseVCards(text).single()
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 10)
        assertEquals("vend", c.note)
    }

    @Test fun websiteLaunchUrl_onlyHttpAndHttps() {
        assertEquals("https://example.com/dana", websiteLaunchUrl("https://example.com/dana"))
        assertEquals("http://example.com", websiteLaunchUrl(" http://example.com "))
        assertEquals("HTTPS://EXAMPLE.COM", websiteLaunchUrl("HTTPS://EXAMPLE.COM"))
        assertEquals("https://example.com", websiteLaunchUrl("example.com"))
        assertEquals("https://example.com:8080/x", websiteLaunchUrl("example.com:8080/x"))
        assertEquals("https://localhost:3000", websiteLaunchUrl("localhost:3000"))
        for (bad in listOf(
            "file:///etc/passwd", "content://com.android.contacts/contacts", "intent://x#Intent;scheme=zxing;end",
            "myapp://deep/link", "JAVASCRIPT://x", "javascript:alert(1)", "sms:5550100033", "tel:5550100033",
            "mailto:x@example.com", "market://details?id=x", "", "   ", "https://exa mple.com",
            "sms:12345", "zxing:12345/x", "example.com:123456",
        )) assertNull(bad, websiteLaunchUrl(bad))
        // Such values are still read into the card (copyable), only not launched.
        val c = parseVCards("BEGIN:VCARD\nFN:X\nURL:file:///etc/passwd\nURL:example.org\nEND:VCARD").single()
        assertEquals(listOf("file:///etc/passwd", "example.org"), c.urls.map { it.value })
    }

    // ---- several cards, damage, caps ----

    @Test fun twoCardsInOneFile() {
        val text = "BEGIN:VCARD\nFN:First Person\nTEL:555-010-0012\nEND:VCARD\n\nBEGIN:VCARD\nFN:Second Person\nEMAIL:second@example.com\nEND:VCARD\n"
        val cards = parseVCards(text)
        assertEquals(listOf("First Person", "Second Person"), cards.map { it.displayName })
        assertEquals("555-010-0012", cards[0].phones.single().value)
        assertTrue(cards[0].emails.isEmpty())
        assertEquals("second@example.com", cards[1].emails.single().value)
    }

    @Test fun truncatedFile_keepsWhatWasRead() {
        val whole = iphoneCard()
        val cut = whole.substring(0, whole.indexOf("PHOTO;") + 60)   // mid-photo, no END
        val c = parseVCards(cut).single()
        assertEquals("Dr. Dana Lee Quimby Jr.", c.displayName)
        assertEquals(4, c.phones.size)
        assertNull(c.photo)   // a cut base64 run is not a photo
    }

    @Test fun truncatedInsideAName_stillYieldsTheCard() {
        val c = parseVCards("BEGIN:VCARD\r\nVERSION:3.0\r\nTEL;type=CELL:555-010-0013\r\nFN:Half Na").single()
        assertEquals("Half Na", c.displayName)
    }

    @Test fun bogusBase64Photo_isDroppedCardKept() {
        val text = "BEGIN:VCARD\nFN:Pat Example\nTEL:555-010-0014\nPHOTO;ENCODING=b;TYPE=JPEG:this is not base64!!!\nEND:VCARD"
        val c = parseVCards(text).single()
        assertEquals("Pat Example", c.displayName)
        assertNull(c.photo)
        assertNull(c.photoMime)
    }

    @Test fun photoUrl_isNotFetched() {
        val c = parseVCards("BEGIN:VCARD\nFN:Pat Example\nPHOTO;VALUE=uri:https://example.com/p.jpg\nTEL:555-010-0015\nEND:VCARD").single()
        assertNull(c.photo)
    }

    @Test fun oversizedPhoto_neverComesBackOverTheCap() {
        // Over the photo cap and, encoded, over the input cap too: the card before it is still read.
        val big = ByteArray(VCARD_MAX_PHOTO_BYTES + 1) { 'A'.code.toByte() }
        val text = "BEGIN:VCARD\nFN:Pat Example\nTEL:555-010-0016\nPHOTO;ENCODING=b;TYPE=JPEG:" +
            Base64.getEncoder().encodeToString(big) + "\nEND:VCARD"
        val c = parseVCards(text).single()
        assertTrue(c.photo == null || c.photo!!.size <= VCARD_MAX_PHOTO_BYTES)
        assertEquals("555-010-0016", c.phones.single().value)
    }

    @Test fun oversizedInput_isReadOnlyUpToTheCap() {
        val head = "BEGIN:VCARD\nFN:Early Person\nTEL:555-010-0017\nEND:VCARD\n"
        val filler = "NOTE:" + "x".repeat(VCARD_MAX_INPUT_BYTES.toInt()) + "\n"
        val tail = "BEGIN:VCARD\nFN:Late Person\nTEL:555-010-0018\nEND:VCARD\n"
        val cards = parseVCards(head + filler + tail)
        assertEquals(listOf("Early Person"), cards.map { it.displayName })
    }

    @Test fun onlyTel_namedAfterTheNumber() {
        val c = parseVCards("BEGIN:VCARD\nVERSION:3.0\nTEL;type=CELL:555-010-0019\nEND:VCARD").single()
        assertEquals("555-010-0019", c.displayName)
        assertNull(c.givenName)
        assertEquals("555-010-0019", contactSecondaryLine(c))
    }

    @Test fun displayName_fallsBackFromFnToNToOrgToEmail() {
        assertEquals("Dana Quimby", parseVCards("BEGIN:VCARD\nN:Quimby;Dana;;;\nTEL:555-010-0020\nEND:VCARD").single().displayName)
        assertEquals("Quimby", parseVCards("BEGIN:VCARD\nN:Quimby;;;;\nTEL:555-010-0020\nEND:VCARD").single().displayName)
        assertEquals("Acme Co", parseVCards("BEGIN:VCARD\nORG:Acme Co;Sales\nTEL:555-010-0020\nEND:VCARD").single().displayName)
        assertEquals("only@example.com", parseVCards("BEGIN:VCARD\nEMAIL:only@example.com\nEND:VCARD").single().displayName)
    }

    @Test fun emptyOrNamelessCards_areDropped() {
        assertTrue(parseVCards("").isEmpty())
        assertTrue(parseVCards("garbage\nmore garbage: with colon\n").isEmpty())
        assertTrue(parseVCards("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD").isEmpty())
        assertTrue(parseVCards("BEGIN:VCARD\nNOTE:just a note\nURL:https://example.com\nEND:VCARD").isEmpty())
        assertTrue(parseVCards("BEGIN:VCARD\nFN:   \nN:;;;;\nEND:VCARD").isEmpty())
    }

    @Test fun garbageBetweenAndInsideCards_isIgnored() {
        val text = "::\nBEGIN:VCARD\n;;;:\n\u0000\u0001\nFN:Robust Person\nNOT A LINE\nTEL;:555-010-0021\nEND:VCARD\nleftover"
        val c = parseVCards(text).single()
        assertEquals("Robust Person", c.displayName)
        assertEquals(listOf(Labeled("Other", "555-010-0021")), c.phones)
    }

    @Test fun beginWithoutEnd_startsANewCard() {
        val text = "BEGIN:VCARD\nFN:One\nTEL:555-010-0022\nBEGIN:VCARD\nFN:Two\nTEL:555-010-0023\nEND:VCARD"
        assertEquals(listOf("One", "Two"), parseVCards(text).map { it.displayName })
    }

    @Test fun caps_tenCardsAndTwentyEntries() {
        val many = (1..12).joinToString("") { "BEGIN:VCARD\nFN:Person $it\nTEL:555-010-00$it\nEND:VCARD\n" }
        assertEquals(VCARD_MAX_CARDS, parseVCards(many).size)

        val tels = (1..25).joinToString("\n") { "TEL;TYPE=HOME:555-010-%04d".format(it) }
        val c = parseVCards("BEGIN:VCARD\nFN:Many Numbers\n$tels\nEND:VCARD").single()
        assertEquals(VCARD_MAX_ENTRIES, c.phones.size)
        assertEquals("555-010-0001", c.phones.first().value)
    }

    @Test fun bomIsDropped() {
        val text = vcardText("﻿BEGIN:VCARD\nFN:Bom Person\nTEL:555-010-0024\nEND:VCARD".toByteArray(Charsets.UTF_8))
        assertEquals("Bom Person", parseVCards(text).single().displayName)
    }

    @Test fun labels_coverTheCommonTypes() {
        fun label(params: String) = parseVCards("BEGIN:VCARD\nFN:X\nTEL$params:555-010-0025\nEND:VCARD").single().phones.single().label
        assertEquals("Mobile", label(";TYPE=CELL"))
        assertEquals("Mobile", label(";type=iPhone;type=CELL"))
        assertEquals("Home", label(";TYPE=HOME,VOICE"))
        assertEquals("Work", label(";TYPE=work"))
        assertEquals("Main", label(";TYPE=MAIN"))
        assertEquals("Fax", label(";TYPE=WORK,FAX"))
        assertEquals("Other", label(""))
        assertEquals("Other", label(";TYPE=VOICE"))
    }

    @Test fun secondaryLine_phoneThenEmailThenOrg() {
        assertEquals("dana@example.com", contactSecondaryLine(parseVCards("BEGIN:VCARD\nFN:X\nORG:Acme\nEMAIL:dana@example.com\nEND:VCARD").single()))
        assertEquals("Acme", contactSecondaryLine(parseVCards("BEGIN:VCARD\nFN:X\nORG:Acme\nEND:VCARD").single()))
        assertNull(contactSecondaryLine(parseVCards("BEGIN:VCARD\nFN:X\nEND:VCARD").single()))
    }

    @Test fun photoSampleSize_powersOfTwoNeverBelowTarget() {
        assertEquals(1, photoSampleSize(100, 100, 128))
        assertEquals(1, photoSampleSize(200, 200, 128))
        assertEquals(2, photoSampleSize(256, 256, 128))
        assertEquals(4, photoSampleSize(1000, 600, 128))
        assertEquals(128, photoSampleSize(20000, 20000, 128))
        assertEquals(1, photoSampleSize(0, 10, 128))
        assertEquals(1, photoSampleSize(10, 10, 0))
    }
}
