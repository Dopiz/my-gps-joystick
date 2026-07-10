package com.dopiz.gpsjoystick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date

/**
 * Batch 2: the GPX library. Lists saved GPX (name / point count / date), imports new files
 * (copying them into internal storage via [GpxStore]), and per-item rename / export / delete.
 * Tapping a row CHOOSES that GPX for playback: it returns the id to [MapActivity] via the
 * Activity Result API (and marks it "current" in [SessionStore] so the map reloads it later).
 */
class GpxLibraryActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView

    /** The entry currently awaiting an export destination (set just before launching CREATE_DOCUMENT). */
    private var pendingExportId: String? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImport(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
            val id = pendingExportId
            pendingExportId = null
            if (uri != null && id != null) doExport(id, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gpx_library)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        listContainer = findViewById(R.id.listContainer)
        emptyHint = findViewById(R.id.emptyHint)

        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(
                arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")
            )
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val entries = GpxStore.list(this)
        emptyHint.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val inflater = LayoutInflater.from(this)
        val dateFmt = android.text.format.DateFormat.getDateFormat(this)
        entries.forEach { e ->
            val row = inflater.inflate(R.layout.item_gpx, listContainer, false)
            row.findViewById<TextView>(R.id.gpxName).text = e.name
            row.findViewById<TextView>(R.id.gpxMeta).text =
                getString(R.string.gpx_meta, e.pointCount, dateFmt.format(Date(e.importedAt)))
            row.findViewById<LinearLayout>(R.id.rowChoose).setOnClickListener { choose(e) }
            row.findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener { v ->
                showItemMenu(v, e)
            }
            listContainer.addView(row)
        }
    }

    private fun choose(e: GpxStore.Entry) {
        SessionStore.saveCurrentGpxId(this, e.id)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_GPX_ID, e.id))
        finish()
    }

    private fun showItemMenu(anchor: android.view.View, e: GpxStore.Entry) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.gpx_item, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { renameDialog(e); true }
                    R.id.action_export -> { pendingExportId = e.id; exportLauncher.launch(e.originalFilename); true }
                    R.id.action_delete -> { deleteDialog(e); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun doImport(uri: Uri) {
        val entry = try {
            GpxStore.import(this, uri, System.currentTimeMillis())
        } catch (e: GpxStore.EmptyGpxException) {
            Toast.makeText(this, R.string.gpx_empty, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.gpx_import_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this, getString(R.string.gpx_imported, entry.name, entry.pointCount), Toast.LENGTH_SHORT
        ).show()
        render()
    }

    private fun doExport(id: String, dest: Uri) {
        val ok = runCatching {
            val bytes = GpxStore.file(this, id).readBytes()
            contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                ?: throw java.io.IOException("no stream")
        }.isSuccess
        val name = GpxStore.list(this).firstOrNull { it.id == id }?.name ?: ""
        Toast.makeText(
            this,
            if (ok) getString(R.string.gpx_export_ok, name) else getString(R.string.gpx_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun renameDialog(e: GpxStore.Entry) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(e.name)
            setSelection(text.length)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gpx_rename_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                GpxStore.rename(this, e.id, input.text.toString())
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteDialog(e: GpxStore.Entry) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.gpx_delete_confirm, e.name))
            .setPositiveButton(R.string.gpx_delete) { _, _ ->
                GpxStore.delete(this, e.id)
                if (SessionStore.loadCurrentGpxId(this) == e.id) SessionStore.clearCurrentGpxId(this)
                Toast.makeText(this, getString(R.string.gpx_deleted, e.name), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_GPX_ID = "gpx_id"
    }
}
