package org.telegram.PhoneFormat;

import android.content.Context;
import android.content.res.AssetManager;

import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PhoneFormatTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = null;
    }

    @Test
    public void testStripExceptNumbers() {
        assertEquals("+18005550199", PhoneFormat.stripExceptNumbers("+1 (800) 555-0199", true));
        assertEquals("18005550199", PhoneFormat.stripExceptNumbers("+1 (800) 555-0199", false));
        assertEquals("+12345", PhoneFormat.stripExceptNumbers("abc+12345-xyz", true));
        assertEquals("12345", PhoneFormat.stripExceptNumbers("abc+12345-xyz", false));
        assertNull(PhoneFormat.stripExceptNumbers(null, true));
        assertNull(PhoneFormat.stripExceptNumbers(null, false));
    }

    @Test
    public void testFormatFallbackWhenUninitialized() {
        ApplicationLoader.applicationContext = null;
        PhoneFormat instance = PhoneFormat.getInstance();

        assertEquals("+18005550199", instance.format("+18005550199"));
        assertEquals("12345", instance.format("12345"));
    }

    @Test
    public void testFormatWithAssetData() throws Exception {
        File assetFile = new File("src/main/assets/PhoneFormats.dat");
        if (!assetFile.exists()) {
            assetFile = new File("app/src/main/assets/PhoneFormats.dat");
        }

        if (assetFile.exists()) {
            Context context = mock(Context.class);
            AssetManager assetManager = mock(AssetManager.class);
            File finalAssetFile = assetFile;
            when(assetManager.open(anyString())).thenAnswer(invocation -> new FileInputStream(finalAssetFile));
            when(context.getAssets()).thenReturn(assetManager);

            ApplicationLoader.applicationContext = context;

            PhoneFormat instance = PhoneFormat.getInstance();
            instance.init("us");

            String formattedUS = instance.format("+12025550123");
            assertEquals("+1 (202) 555-0123", formattedUS);

            String formattedRU = instance.format("+79123456789");
            assertEquals("+7 (912) 345-67-89", formattedRU);
        }
    }
}
