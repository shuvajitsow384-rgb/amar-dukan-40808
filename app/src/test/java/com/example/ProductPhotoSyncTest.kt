package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.Product
import com.example.data.repository.StoreRepository
import com.example.utils.ImageSyncHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductPhotoSyncTest {

    @Test
    fun `test real phone photo compression and base64 size ceiling under 1MB Firestore limit`() {
        // Create simulated 12MP high-resolution photo from phone camera (4032 x 3024)
        val highResBitmap = Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888)
        for (x in 0 until 100) {
            highResBitmap.setPixel(x * 10, x * 10, Color.rgb(200, 100, 50))
        }

        val dataUrl = ImageSyncHelper.compressBitmapToDataUrl(highResBitmap)

        assertNotNull(dataUrl)
        assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"))

        // Ensure compressed Base64 string is well under 100 KB (< 10% of Firestore 1MB limit)
        val payloadLength = dataUrl.length
        println("Simulated 12MP (4032x3024) camera photo -> Compressed Data URL length: $payloadLength chars (~${payloadLength / 1024} KB)")

        assertTrue("Payload should be under 100KB for rapid Firestore sync", payloadLength < 100_000)
        assertTrue("Payload should be meaningful (> 1KB)", payloadLength > 1000)

        // Verify getImageModel decodes it to a ByteArray for Coil AsyncImage
        val model = ImageSyncHelper.getImageModel(dataUrl)
        assertTrue("Model for AsyncImage must be a ByteArray", model is ByteArray)
        val decodedBytes = model as ByteArray
        assertTrue("Decoded bytes must not be empty", decodedBytes.isNotEmpty())
        println("Decoded JPEG byte array size for AsyncImage: ${decodedBytes.size} bytes (${decodedBytes.size / 1024} KB)")
    }

    @Test
    fun `test legacy local file URI migration to Base64 Data URL`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getDatabase(context)
        val repository = StoreRepository(db, context)

        // 1. Create a local temporary photo file simulating an older device-local photo
        val tempPhotoFile = File(context.cacheDir, "legacy_product_photo.jpg")
        val sampleBitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        FileOutputStream(tempPhotoFile).use { out ->
            sampleBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        val legacyUri = "file://${tempPhotoFile.absolutePath}"

        // 2. Insert a product with the legacy file:// URI directly into Room
        val legacyProduct = Product(
            id = "prod_legacy_test_01",
            nameEn = "Legacy Oil Pouch",
            nameBn = "তেল",
            category = "Groceries",
            unitType = "ltr",
            costPrice = 120.0,
            sellingPrice = 140.0,
            currentStock = 20.0,
            imageUri = legacyUri
        )
        db.productDao().insertProduct(legacyProduct)

        // Verify product initially has legacy URI
        val preMigration = db.productDao().getProductById("prod_legacy_test_01")
        assertEquals(legacyUri, preMigration?.imageUri)

        // 3. Trigger automatic legacy image migration
        repository.autoMigrateLegacyImageUris()

        // 4. Verify product in Room now has a syncable Base64 Data URL
        val postMigration = db.productDao().getProductById("prod_legacy_test_01")
        assertNotNull(postMigration)
        assertNotNull(postMigration?.imageUri)
        assertTrue(
            "Migrated imageUri must be a Base64 data URL",
            postMigration!!.imageUri!!.startsWith("data:image/jpeg;base64,")
        )
        println("Successfully migrated legacy product image: ${postMigration.imageUri?.take(40)}... (total chars: ${postMigration.imageUri?.length})")

        // 5. Test prepareProductForSync
        val prepared = repository.prepareProductForSync(legacyProduct)
        assertTrue(prepared.imageUri!!.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `test web URL and null handling in ImageSyncHelper`() {
        val webUrl = "https://images.unsplash.com/photo-1542838132-92c53300491e"
        val processed = ImageSyncHelper.processImageUriForSync(
            ApplicationProvider.getApplicationContext(),
            webUrl
        )
        assertEquals(webUrl, processed)

        val model = ImageSyncHelper.getImageModel(webUrl)
        assertEquals(webUrl, model)

        assertNull(ImageSyncHelper.getImageModel(null))
        assertNull(ImageSyncHelper.getImageModel(""))
        assertNull(ImageSyncHelper.getImageModel("   "))
    }
}
