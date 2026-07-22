package com.dopiz.gpsjoystick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Favorites as a full screen, mirroring [GpxLibraryActivity]: a Material Toolbar (back + title),
 * a list of saved favorites (name + "lat, lng"), and a per-row overflow menu for 重新命名 / 刪除.
 * Tapping a row CHOOSES that favorite: it returns the coordinate to [MapActivity] via the
 * Activity Result API (EXTRA_FAV_LAT / EXTRA_FAV_LNG).
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImport(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { doExport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        listContainer = findViewById(R.id.listContainer)
        emptyHint = findViewById(R.id.emptyHint)

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            if (FavoritesStore.list(this).isEmpty()) {
                Toast.makeText(this, R.string.fav_export_empty, Toast.LENGTH_SHORT).show()
            } else {
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                exportLauncher.launch("favorites-$stamp.json")
            }
        }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun doExport(dest: Uri) {
        val json = FavoritesStore.exportJson(this)
        val count = FavoritesStore.list(this).size
        val ok = runCatching {
            contentResolver.openOutputStream(dest)?.use { it.write(json.toByteArray()) }
                ?: throw java.io.IOException("no stream")
        }.isSuccess
        Toast.makeText(
            this,
            if (ok) getString(R.string.fav_export_ok, count) else getString(R.string.fav_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun doImport(uri: Uri) {
        val result = runCatching {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw java.io.IOException("no stream")
            FavoritesStore.importMerged(this, json)
        }
        result.onSuccess { (imported, skipped) ->
            Toast.makeText(
                this, getString(R.string.fav_imported, imported, skipped), Toast.LENGTH_SHORT
            ).show()
            render()
        }.onFailure { e ->
            Toast.makeText(
                this, getString(R.string.fav_import_failed, e.message ?: "?"), Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val favs = FavoritesStore.list(this)
        val current = MockLocationService.state.value
        emptyHint.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        favs.forEachIndexed { index, fav ->
            val row = inflater.inflate(R.layout.item_favorite, listContainer, false)
            val estimate = PokemonGoCooldown.estimate(
                current.latitude, current.longitude, fav.lat, fav.lng
            )
            row.findViewById<TextView>(R.id.favName).text = fav.label
            row.findViewById<TextView>(R.id.favMeta).text = "${fmt(fav.lat)}, ${fmt(fav.lng)}"
            row.findViewById<TextView>(R.id.favCooldown).text = getString(
                R.string.fav_cooldown,
                formatDistance(estimate.distanceMeters),
                formatCooldown(estimate.waitSeconds),
            )
            row.findViewById<MaterialButton>(R.id.btnFavWalk).setOnClickListener {
                choose(fav, ACTION_WALK)
            }
            row.findViewById<MaterialButton>(R.id.btnFavTeleport).setOnClickListener {
                choose(fav, ACTION_TELEPORT)
            }
            row.findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener { v ->
                showItemMenu(v, index, fav)
            }
            listContainer.addView(row)
        }
    }

    private fun choose(fav: FavoritesStore.Fav, action: String) {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_FAV_LAT, fav.lat)
                .putExtra(EXTRA_FAV_LNG, fav.lng)
                .putExtra(EXTRA_FAV_ACTION, action),
        )
        finish()
    }

    private fun showItemMenu(anchor: View, index: Int, fav: FavoritesStore.Fav) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.favorite_item, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { renameDialog(index, fav); true }
                    R.id.action_delete -> { deleteDialog(index, fav); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun renameDialog(index: Int, fav: FavoritesStore.Fav) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(fav.label)
            setSelection(text.length)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fav_rename_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    FavoritesStore.rename(this, index, name)
                    render()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteDialog(index: Int, fav: FavoritesStore.Fav) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.fav_delete_confirm, fav.label))
            .setPositiveButton(R.string.gpx_delete) { _, _ ->
                FavoritesStore.removeAt(this, index)
                Toast.makeText(this, getString(R.string.fav_deleted, fav.label), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun fmt(v: Double): String = "%.5f".format(v)

    private fun formatDistance(meters: Double): String = when {
        meters < 1_000.0 -> getString(R.string.distance_meters, meters.toInt())
        meters < 100_000.0 -> getString(R.string.distance_km_decimal, meters / 1_000.0)
        else -> getString(R.string.distance_km_whole, (meters / 1_000.0).toInt())
    }

    private fun formatCooldown(seconds: Int): String = when {
        seconds == 0 -> getString(R.string.cooldown_none)
        seconds < 60 -> getString(R.string.cooldown_seconds, seconds)
        seconds % 3_600 == 0 -> getString(R.string.cooldown_hours, seconds / 3_600)
        seconds >= 3_600 -> getString(
            R.string.cooldown_hours_minutes, seconds / 3_600, (seconds % 3_600) / 60
        )
        else -> getString(R.string.cooldown_minutes, seconds / 60)
    }

    companion object {
        const val EXTRA_FAV_LAT = "fav_lat"
        const val EXTRA_FAV_LNG = "fav_lng"
        const val EXTRA_FAV_ACTION = "fav_action"
        const val ACTION_TELEPORT = "teleport"
        const val ACTION_WALK = "walk"
    }
}
