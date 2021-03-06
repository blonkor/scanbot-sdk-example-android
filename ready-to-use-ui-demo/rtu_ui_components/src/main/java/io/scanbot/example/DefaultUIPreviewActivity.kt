package io.scanbot.example

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.scanbot.example.fragments.BarCodeDialogFragment
import io.scanbot.example.fragments.ErrorFragment
import io.scanbot.example.fragments.MRZDialogFragment
import io.scanbot.example.fragments.QRCodeDialogFragment
import io.scanbot.example.repository.PageRepository
import io.scanbot.mrzscanner.model.MRZRecognitionResult
import io.scanbot.sdk.ScanbotSDK
import io.scanbot.sdk.barcode.entity.BarcodeScanningResult
import io.scanbot.sdk.persistence.Page
import io.scanbot.sdk.process.ImageFilterType
import io.scanbot.sdk.ui.view.barcode.BarcodeScannerActivity
import io.scanbot.sdk.ui.view.barcode.configuration.BarcodeScannerConfiguration
import io.scanbot.sdk.ui.view.camera.DocumentScannerActivity
import io.scanbot.sdk.ui.view.camera.configuration.DocumentScannerConfiguration
import io.scanbot.sdk.ui.view.edit.configuration.CroppingConfiguration
import io.scanbot.sdk.ui.view.mrz.MRZScannerActivity
import io.scanbot.sdk.ui.view.mrz.configuration.MRZScannerConfiguration
import kotlinx.android.synthetic.main.activity_default_preview.*
import net.doo.snap.camera.CameraPreviewMode
import net.doo.snap.lib.detector.DetectionResult
import java.io.IOException


class DefaultUIPreviewActivity : AppCompatActivity() {

    companion object {
        private const val MRZ_DEFAULT_UI_REQUEST_CODE = 909
        private const val BARCODE_DEFAULT_UI_REQUEST_CODE = 910
        private const val QR_CODE_DEFAULT_UI_REQUEST_CODE = 911
        private const val CROP_DEFAULT_UI_REQUEST_CODE = 9999
        private const val SELECT_PICTURE_FOR_CROPPING_UI_REQUEST = 8888
        private const val SELECT_PICTURE_FOR_DOC_DETECTION_REQUEST = 7777
        private const val CAMERA_DEFAULT_UI_REQUEST_CODE = 1111
    }

    private lateinit var scanbotSDK: ScanbotSDK

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == MRZ_DEFAULT_UI_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            showMrzDialog(data!!.getParcelableExtra(MRZScannerActivity.EXTRACTED_FIELDS_EXTRA))
        } else if (requestCode == CROP_DEFAULT_UI_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val page = data!!.getParcelableExtra<Page>(io.scanbot.sdk.ui.view.edit.CroppingActivity.EDITED_PAGE_EXTRA)
            page.pageId
        } else if (requestCode == BARCODE_DEFAULT_UI_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val barcodeData = data!!.getParcelableExtra<BarcodeScanningResult>(BarcodeScannerActivity.SCANNED_BARCODE_EXTRA)
            showBarcodeDialog(barcodeData)
        } else if (requestCode == QR_CODE_DEFAULT_UI_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val qrCode = data!!.getParcelableExtra<BarcodeScanningResult>(BarcodeScannerActivity.SCANNED_BARCODE_EXTRA)
            showQrDialog(qrCode)
        } else if (requestCode == SELECT_PICTURE_FOR_CROPPING_UI_REQUEST) {
            if (resultCode == RESULT_OK) {
                ProcessImageForCroppingUI(data).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR)
            }
        } else if (requestCode == SELECT_PICTURE_FOR_DOC_DETECTION_REQUEST) {
            if (resultCode == RESULT_OK) {
                if (!scanbotSDK.isLicenseValid) {
                    showLicenseDialog()
                } else {
                    ProcessImageForAutoDocumentDetection(data).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR)
                }
            }
        } else if (requestCode == CAMERA_DEFAULT_UI_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val pages = data!!.getParcelableArrayExtra(DocumentScannerActivity.SNAPPED_PAGE_EXTRA).toList().map {
                it as Page
            }

            PageRepository.addPages(pages)

            val intent = Intent(this@DefaultUIPreviewActivity, PagePreviewActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showLicenseDialog() {
        if (supportFragmentManager.findFragmentByTag(ErrorFragment.NAME) == null) {
            val dialogFragment = ErrorFragment.newInstanse()
            dialogFragment.show(supportFragmentManager, ErrorFragment.NAME)
        }
    }

    private fun showMrzDialog(mrzRecognitionResult: MRZRecognitionResult) {
        val dialogFragment = MRZDialogFragment.newInstanse(mrzRecognitionResult)
        dialogFragment.show(supportFragmentManager, MRZDialogFragment.NAME)
    }

    private fun showBarcodeDialog(barcodeRecognitionResult: BarcodeScanningResult) {
        val dialogFragment = BarCodeDialogFragment.newInstanse(barcodeRecognitionResult)
        dialogFragment.show(supportFragmentManager, BarCodeDialogFragment.NAME)
    }

    private fun showQrDialog(barcodeRecognitionResult: BarcodeScanningResult) {
        val fm = supportFragmentManager
        val dialogFragment = QRCodeDialogFragment.newInstanse(barcodeRecognitionResult)
        dialogFragment.show(fm, QRCodeDialogFragment.NAME)
    }

    override fun onResume() {
        super.onResume()
        if (!scanbotSDK.isLicenseValid) {
            showLicenseDialog()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initDependencies()
        setContentView(R.layout.activity_default_preview)

//        val spannable = SpannableString(getString(R.string.scanbot_sdk_title))
//        spannable.setSpan(
//                FontSpan(Typeface.SANS_SERIF),
//                0, 4,
//                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
//
//        textViewtitle.text = spannable

        // select an image from photo library and run document detection on it:
        findViewById<View>(R.id.doc_detection_on_image_btn).setOnClickListener {
            val imageIntent = Intent()
            imageIntent.type = "image/*"
            imageIntent.action = Intent.ACTION_GET_CONTENT
            imageIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                imageIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
            startActivityForResult(Intent.createChooser(imageIntent, getString(R.string.share_title)), SELECT_PICTURE_FOR_DOC_DETECTION_REQUEST)
        }

        findViewById<View>(R.id.camera_default_ui).setOnClickListener {
            val cameraConfiguration = DocumentScannerConfiguration()
            cameraConfiguration.setCameraPreviewMode(CameraPreviewMode.FIT_IN)
            cameraConfiguration.setIgnoreBadAspectRatio(true)
            cameraConfiguration.setBottomBarBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            cameraConfiguration.setBottomBarButtonsColor(ContextCompat.getColor(this, R.color.greyColor))
            cameraConfiguration.setTopBarButtonsActiveColor(ContextCompat.getColor(this, android.R.color.white))
            cameraConfiguration.setCameraBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary))
            cameraConfiguration.setUserGuidanceBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
            cameraConfiguration.setUserGuidanceTextColor(ContextCompat.getColor(this, android.R.color.white))

            val intent = io.scanbot.sdk.ui.view.camera.DocumentScannerActivity.newIntent(this@DefaultUIPreviewActivity,
                    cameraConfiguration
            )
            startActivityForResult(intent, CAMERA_DEFAULT_UI_REQUEST_CODE)
        }

        findViewById<View>(R.id.page_preview_activity).setOnClickListener {
            val intent = Intent(this@DefaultUIPreviewActivity, PagePreviewActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.mrz_camera_default_ui).setOnClickListener {
            val mrzCameraConfiguration = MRZScannerConfiguration()

            mrzCameraConfiguration.setTopBarBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            mrzCameraConfiguration.setTopBarButtonsColor(ContextCompat.getColor(this, R.color.greyColor))

            val intent = MRZScannerActivity.newIntent(this@DefaultUIPreviewActivity, mrzCameraConfiguration)
            startActivityForResult(intent, MRZ_DEFAULT_UI_REQUEST_CODE)
        }

        findViewById<View>(R.id.qr_camera_default_ui).setOnClickListener {
            val barcodeCameraConfiguration = BarcodeScannerConfiguration()

            barcodeCameraConfiguration.setTopBarButtonsColor(ContextCompat.getColor(this, android.R.color.white))
            barcodeCameraConfiguration.setTopBarBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimaryDark))
            barcodeCameraConfiguration.setFinderTextHint("Please align the QR-/Barcode in the frame above to scan it.")

            val intent = BarcodeScannerActivity.newIntent(this@DefaultUIPreviewActivity, barcodeCameraConfiguration)
            startActivityForResult(intent, BARCODE_DEFAULT_UI_REQUEST_CODE)
        }

    }

    private fun processGalleryResult(data: Intent): Bitmap? {
        val imageUri = data.data
        var bitmap: Bitmap? = null
        if (imageUri != null) {
            try {
                bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            } catch (e: IOException) {
            }
        }
        return bitmap
    }

    private fun initDependencies() {
        scanbotSDK = ScanbotSDK(this)
    }

    /**
     * Imports a selected image as original image, creates a new page and opens the Cropping UI on it.
     */
    internal inner class ProcessImageForCroppingUI(private var data: Intent?) : AsyncTask<Void, Void, Page>() {

        override fun onPreExecute() {
            super.onPreExecute()
            progressBar.visibility = View.VISIBLE
        }

        override fun doInBackground(vararg params: Void): Page {
            val processGalleryResult = processGalleryResult(data!!)

            val pageFileStorage = io.scanbot.sdk.ScanbotSDK(this@DefaultUIPreviewActivity).pageFileStorage()
            // create a new Page object with given image as original image:
            val pageId = pageFileStorage.add(processGalleryResult!!)
            return Page(pageId)
        }

        override fun onPostExecute(page: Page) {
            progressBar.visibility = View.GONE
            val editPolygonConfiguration = CroppingConfiguration()

            editPolygonConfiguration.setPage(page)

            val intent = io.scanbot.sdk.ui.view.edit.CroppingActivity.newIntent(
                    applicationContext,
                    editPolygonConfiguration
            )
            startActivityForResult(intent, CROP_DEFAULT_UI_REQUEST_CODE)
        }
    }


    /**
     * Imports a selected image as original image and performs auto document detection on it.
     */
    internal inner class ProcessImageForAutoDocumentDetection(private var data: Intent?) : AsyncTask<Void, Void, Page>() {

        override fun onPreExecute() {
            super.onPreExecute()
            progressBar.visibility = View.VISIBLE
            Toast.makeText(this@DefaultUIPreviewActivity,
                    getString(R.string.importing_and_processing), Toast.LENGTH_LONG).show()
        }

        override fun doInBackground(vararg params: Void): Page {
            val processGalleryResult = processGalleryResult(data!!)

            val pageFileStorage = io.scanbot.sdk.ScanbotSDK(this@DefaultUIPreviewActivity).pageFileStorage()
            val pageProcessor = io.scanbot.sdk.ScanbotSDK(this@DefaultUIPreviewActivity).pageProcessor()

            // create a new Page object with given image as original image:
            val pageId = pageFileStorage.add(processGalleryResult!!)
            var page = Page(pageId, emptyList(), DetectionResult.OK, ImageFilterType.NONE)

            // run auto document detection on it:
            page = pageProcessor.detectDocument(page)

            PageRepository.addPage(page)

            return page
        }

        override fun onPostExecute(page: Page) {
            progressBar.visibility = View.GONE
            val intent = Intent(this@DefaultUIPreviewActivity, PagePreviewActivity::class.java)
            startActivity(intent)
        }
    }

}