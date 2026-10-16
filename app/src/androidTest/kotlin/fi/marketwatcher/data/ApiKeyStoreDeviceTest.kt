package fi.marketwatcher.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.marketwatcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** On a phone or emulator: encryption, migration from plain text, and backup exclusions. */
@RunWith(AndroidJUnit4::class)
class ApiKeyStoreDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = "4f8c2e91b7d34a06c5e1f92a7b3d1a2b"
    private fun prefsFile(name: String) = File(ctx.applicationInfo.dataDir, "shared_prefs/$name.xml")

    @Before fun clean() {
        ApiKeyStore(ctx).clear()
        ctx.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun saveAndLoadRoundTrip() {
        val store = ApiKeyStore(ctx)
        store.save(key)
        assertEquals(ApiKeyStore.Loaded.Key(key), store.load())
        assertFalse("ciphertext only", prefsFile(ApiKeyStore.FILE).readText().contains(key))
    }

    @Test fun tamperedCiphertextIsRemovedNotCrashing() {
        ApiKeyStore(ctx).save(key)
        val p = ctx.getSharedPreferences(ApiKeyStore.FILE, Context.MODE_PRIVATE)
        val (iv, ct) = p.getString("fred_api_key_enc", null)!!.split(":")
        val flipped = (if (ct[0] == 'A') 'B' else 'A') + ct.substring(1)
        p.edit().putString("fred_api_key_enc", "$iv:$flipped").commit()
        assertEquals(ApiKeyStore.Loaded.Unreadable, ApiKeyStore(ctx).load())
        assertFalse(ApiKeyStore(ctx).has())
    }

    @Test fun migrationRemovesPlainTextFromDisk() {
        ctx.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE).edit().putString(Settings.LEGACY_KEY, key).commit()
        assertTrue(prefsFile(Settings.FILE).readText().contains(key))
        val s = Settings(ctx)
        s.migrateKey()
        assertFalse("plain text gone from the file", prefsFile(Settings.FILE).readText().contains(key))
        assertEquals(key, s.apiKey)
        assertTrue(s.keyMigratedAt != null)
        s.migrateKey()   // running again changes nothing
        assertEquals(key, Settings(ctx).apiKey)
    }

    @Test fun backupsExcludeBothKeyFiles() {
        for (res in listOf(R.xml.data_extraction_rules, R.xml.backup_rules)) {
            val excluded = mutableListOf<String>()
            ctx.resources.getXml(res).use { x ->
                while (x.next() != XmlPullParser.END_DOCUMENT) {
                    if (x.eventType == XmlPullParser.START_TAG && x.name == "exclude") excluded += x.getAttributeValue(null, "path")
                }
            }
            assertTrue(excluded.contains("${ApiKeyStore.FILE}.xml"))
            assertTrue(excluded.contains("${Settings.FILE}.xml"))
        }
    }
}
