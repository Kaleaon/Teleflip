package org.telegram.PhoneFormat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PhoneFormatTest {

    @Test
    public void testStripStandardNumbers() {
        assertEquals("+18005550199", PhoneFormat.strip("+1 (800) 555-0199"));
        assertEquals("1234567890", PhoneFormat.strip("123-456-7890"));
        assertEquals("+442079460958", PhoneFormat.strip("+44 20 7946 0958"));
    }

    @Test
    public void testStripSpecialSymbols() {
        assertEquals("*#21#", PhoneFormat.strip("*#21#"));
        assertEquals("+123*#", PhoneFormat.strip("+1-23*#"));
    }

    @Test
    public void testStripFullCharacterSetsAndMixedInputs() {
        assertEquals("+123*456#", PhoneFormat.strip("abc+123*456#defXYZ"));
        assertEquals("0123456789+*#", PhoneFormat.strip("0123456789+*# ~!@$%^&()_={}[]|\\:\";'<>,.?/"));
    }

    @Test
    public void testStripEdgeCases() {
        assertEquals("", PhoneFormat.strip(""));
        assertNull(PhoneFormat.strip(null));
        assertEquals("", PhoneFormat.strip("abc DEF !@$"));
    }

    @Test
    public void testStripExceptNumbersIncludePlus() {
        assertEquals("+18005550199", PhoneFormat.stripExceptNumbers("+1 (800) 555-0199", true));
        assertEquals("+123456", PhoneFormat.stripExceptNumbers("abc+123*456#defXYZ", true));
        assertEquals("", PhoneFormat.stripExceptNumbers("", true));
        assertNull(PhoneFormat.stripExceptNumbers(null, true));
    }

    @Test
    public void testStripExceptNumbersExcludePlus() {
        assertEquals("18005550199", PhoneFormat.stripExceptNumbers("+1 (800) 555-0199", false));
        assertEquals("123456", PhoneFormat.stripExceptNumbers("abc+123*456#defXYZ", false));
        assertEquals("", PhoneFormat.stripExceptNumbers("", false));
        assertNull(PhoneFormat.stripExceptNumbers(null, false));
    }

    @Test
    public void testStripExceptNumbersDefaultOverload() {
        assertEquals("18005550199", PhoneFormat.stripExceptNumbers("+1 (800) 555-0199"));
        assertEquals("123456", PhoneFormat.stripExceptNumbers("abc+123*456#defXYZ"));
        assertEquals("", PhoneFormat.stripExceptNumbers(""));
        assertNull(PhoneFormat.stripExceptNumbers(null));
    }
}
