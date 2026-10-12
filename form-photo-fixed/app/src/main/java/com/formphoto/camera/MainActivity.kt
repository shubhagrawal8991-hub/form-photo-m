package com.formphoto.camera

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File

/** Opens the real camera app, but never offers this app itself in the chooser. */
class CamContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            clipData = ClipData.newRawUri("", input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return Intent.createChooser(i, "Camera").putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java))
        )
    }
    override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == -1 // RESULT_OK
}

class MainActivity : ComponentActivity() {
    private lateinit var minEt: EditText
    private lateinit var maxEt: EditText
    private lateinit var dimEt: EditText
    private lateinit var padCb: CheckBox
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var useBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var shareBtn: Button
    private var result: ByteArray? = null
    private var src: Uri? = null
    private var camUri: Uri? = null
    private var outUri: Uri? = null
    private var forResult = false
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }

    private val takePic = registerForActivityResult(CamContract()) { ok -> if (ok) camUri?.let { process(it) } }
    private val pickPic = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) process(u) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(24), px(16), px(24))
        }
        fun add(v: View, top: Int = 10) {
            root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun num(v: String, hint: String) = EditText(this).apply {
            setText(v); this.hint = hint; inputType = InputType.TYPE_CLASS_NUMBER
        }

        add(TextView(this).apply { text = "Form Photo Camera"; textSize = 22f }, 0)

        minEt = num(prefs.getString("mn", "10")!!, "Min KB")
        maxEt = num(prefs.getString("mx", "300")!!, "Max KB")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "Min KB" })
        row.addView(minEt, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "  Max KB" })
        row.addView(maxEt, LinearLayout.LayoutParams(0, -2, 1f))
        add(row)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((a, b) in listOf(10 to 20, 20 to 50, 50 to 100, 100 to 200, 200 to 300)) {
            chips.addView(btn("$a–$b") { minEt.setText("$a"); maxEt.setText("$b"); rerun() })
        }
        add(HorizontalScrollView(this).apply { addView(chips) }, 4)

        dimEt = num(prefs.getString("md", "")!!, "Max width/height px (optional)")
        add(dimEt, 4)
        padCb = CheckBox(this).apply { text = "Pad up to min size if smaller"; isChecked = prefs.getBoolean("pad", true) }
        add(padCb, 0)
        add(btn("Apply range") { rerun() }, 4)

        add(btn("📷  Take photo") { takePhoto() }, 16)
        add(btn("🖼  Choose from gallery") {
            pickPic.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })

        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        root.addView(preview, LinearLayout.LayoutParams(-1, px(260)).apply { topMargin = px(16) })
        status = TextView(this).apply { textSize = 16f }
        add(status, 6)

        useBtn = btn("✔  Use this photo") { deliver() }
        saveBtn = btn("Save to gallery") { saveToGallery() }
        shareBtn = btn("Share") { share() }
        listOf(useBtn, saveBtn, shareBtn).forEach { it.isEnabled = false; add(it, 6) }

        setContentView(ScrollView(this).apply { addView(root) })

        File(cacheDir, "out").listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 3600000L) it.delete()
        }

        val act = intent?.action
        forResult = act == MediaStore.ACTION_IMAGE_CAPTURE || act == Intent.ACTION_GET_CONTENT ||
            act == Intent.ACTION_PICK || act == Intent.ACTION_OPEN_DOCUMENT
        useBtn.visibility = if (forResult) View.VISIBLE else View.GONE
        if (act == MediaStore.ACTION_IMAGE_CAPTURE) {
            outUri = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
        }
        if (act == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { process(it) }
        }
    }

    private fun takePhoto() {
        val f = File(cacheDir, "cap/cap.jpg")
        f.parentFile!!.mkdirs()
        camUri = FileProvider.getUriForFile(this, "$packageName.files", f)
        takePic.launch(camUri!!)
    }

    private fun rerun() { src?.let { process(it) } }

    private fun process(uri: Uri) {
        src = uri
        var mn = (minEt.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1) * 1024
        var mx = (maxEt.text.toString().toIntOrNull() ?: 300).coerceAtLeast(2) * 1024
        if (mn > mx) { val t = mn; mn = mx; mx = t }
        val cap = dimEt.text.toString().toIntOrNull() ?: 0
        val pad = padCb.isChecked
        prefs.edit().putString("mn", minEt.text.toString()).putString("mx", maxEt.text.toString())
            .putString("md", dimEt.text.toString()).putBoolean("pad", pad).apply()
        status.text = "Compressing…"
        listOf(useBtn, saveBtn, shareBtn).forEach { it.isEnabled = false }
        val lo = mn
        val hi = mx
        Thread {
            val out = try { Compressor.run(contentResolver, uri, lo, hi, cap, pad) } catch (e: Exception) { null }
            runOnUiThread { show(out, lo, hi) }
        }.start()
    }

    private fun show(o: Compressor.Out?, mn: Int, mx: Int) {
        if (o == null) {
            result = null
            status.text = "Could not shrink below ${mx / 1024} KB. Try a larger max."
            return
        }
        result = o.bytes
        preview.setImageBitmap(BitmapFactory.decodeByteArray(o.bytes, 0, o.bytes.size))
        val ok = o.bytes.size in mn..mx
        val mark = if (ok) "✓" else "⚠"
        val kb = "%.1f".format(o.bytes.size / 1024.0)
        status.text = "$mark $kb KB · ${o.w}×${o.h}px" + (if (o.padded) " · padded to min" else "")
        listOf(useBtn, saveBtn, shareBtn).forEach { it.isEnabled = true }
    }

    private fun cacheOut(b: ByteArray): Uri {
        val dir = File(cacheDir, "out").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        f.writeBytes(b)
        return FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    /** Hand the compressed photo back to the website / app that asked for it. */
    private fun deliver() {
        val b = result ?: return
        val out = outUri
        if (out != null) {
            contentResolver.openOutputStream(out)?.use { it.write(b) }
            setResult(RESULT_OK)
        } else if (intent.action == MediaStore.ACTION_IMAGE_CAPTURE) {
            val bm = BitmapFactory.decodeByteArray(b, 0, b.size)
            val k = 256f / maxOf(bm.width, bm.height)
            val th = Bitmap.createScaledBitmap(
                bm, (bm.width * k).toInt().coerceAtLeast(1), (bm.height * k).toInt().coerceAtLeast(1), true
            )
            setResult(RESULT_OK, Intent().putExtra("data", th))
        } else {
            val u = cacheOut(b)
            val res = Intent().apply {
                data = u
                clipData = ClipData.newRawUri("", u)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(RESULT_OK, res)
        }
        finish()
    }

    private fun saveToGallery() {
        val b = result ?: return
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "formphoto_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FormPhoto")
        }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (u != null) {
            contentResolver.openOutputStream(u)?.use { it.write(b) }
            Toast.makeText(this, "Saved in Pictures/FormPhoto", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        val b = result ?: return
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, cacheOut(b))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(
            Intent.createChooser(i, "Share photo").putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java))
            )
        )
    }
}
